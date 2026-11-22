package io.github.emadalblueshi.vertx.s3.impl;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.http.HttpHeaders;

/**
 * An upload of a file of known size, without buffering it: each part is a byte range of the file,
 * streamed from its own {@link AsyncFile} handle into the request with an exact
 * {@code Content-Length} ({@link Transport#executeStreamed}), so memory stays at a few chunks per
 * part whatever the file or part size.
 * <ul>
 *   <li>A file of at most one part is one PutObject; a larger one a multipart upload with
 *   {@link #PARTS_IN_FLIGHT} parts streaming at once.</li>
 *   <li>Over HTTPS the parts are sent as {@code UNSIGNED-PAYLOAD}. Over plain HTTP each range is
 *   read twice: once to hash it (incrementally, chunk by chunk), once to send it.</li>
 *   <li>A range that reads short (the file shrank) fails the upload. A file changed in place
 *   without changing its length is uploaded as found.</li>
 *   <li>On any failure, and when the client closes, the requests in flight are reset, and once
 *   they have settled the multipart upload is aborted; the result fails with the original cause (a
 *   failed abort attached as suppressed). Every handle is closed when its range is done.</li>
 * </ul>
 * All state is only touched on the caller's context, where the file handles are opened too.
 */
final class FileUpload implements ActiveUpload {

  static final int PARTS_IN_FLIGHT = 2;

  private final S3ClientImpl client;
  private final ContextSupport.Caller caller;
  private final String bucket;
  private final String key;
  private final List<Map.Entry<String, String>> headers;
  private final String filename;
  private final long size;
  private final int partSize;
  private final int partCount;
  private final boolean hashParts;
  private final Function<String, Future<AsyncFile>> open;
  private final Promise<String> result;

  // State, only touched on the caller's context
  private String uploadId;
  private int nextPartNumber = 1;
  /** Operations in flight: the create, ranges being hashed or sent, the single PUT. */
  private int running;
  private final Map<Integer, Transport.StreamedExchange> exchanges = new HashMap<>();
  private final List<S3CompletedPart> parts = new ArrayList<>();
  private Throwable failure;
  private boolean finishing;

  FileUpload(S3ClientImpl client, ContextSupport.Caller caller, String bucket, String key,
             List<Map.Entry<String, String>> headers, String filename, long size, int partSize, boolean hashParts,
             Function<String, Future<AsyncFile>> open) {
    this.client = client;
    this.caller = caller;
    this.bucket = bucket;
    this.key = key;
    this.headers = headers;
    this.filename = filename;
    this.size = size;
    this.partSize = partSize;
    this.partCount = (int) Math.max(1, (size + partSize - 1) / partSize);
    this.hashParts = hashParts;
    this.open = open;
    this.result = caller.promise();
  }

  /** Starts the upload; must be called on the caller's context. */
  @Override
  public Future<String> start() {
    if (partCount == 1) {
      single();
    } else {
      create();
    }
    return result.future();
  }

  @Override
  public Future<Void> abortOnClose() {
    caller.execute(() -> fail(new S3ClientException("The S3 client was closed during the upload", null)));
    return result.future().transform(ar -> caller.succeeded(null));
  }

  private void single() {
    running = 1;
    Future<String> etag = size == 0
      ? client.putBuffered(caller, bucket, key, headers, Buffer.buffer(), null)
      : sendRange(0, 0, size);
    etag.onComplete(ar -> {
      running--;
      finishing = true;
      client.unregister(this);
      if (ar.succeeded()) {
        result.tryComplete(ar.result());
      } else {
        result.tryFail(failure != null ? failure : ar.cause());
      }
    });
  }

  private void create() {
    running = 1;
    client.createUpload(caller, bucket, key, headers).onComplete(ar -> {
      running--;
      if (ar.succeeded()) {
        uploadId = ar.result();
      } else if (failure == null) {
        failure = ar.cause();
      }
      if (failure != null) {
        settle();
      } else {
        launch();
      }
    });
  }

  /** Starts parts until {@link #PARTS_IN_FLIGHT} are running or all have started. */
  private void launch() {
    while (failure == null && running < PARTS_IN_FLIGHT && nextPartNumber <= partCount) {
      int partNumber = nextPartNumber++;
      long offset = (long) (partNumber - 1) * partSize;
      long length = Math.min(partSize, size - offset);
      running++;
      sendRange(partNumber, offset, length).onComplete(ar -> {
        running--;
        if (ar.succeeded()) {
          parts.add(new S3CompletedPart(partNumber, ar.result()));
        } else {
          fail(ar.cause());
        }
        if (failure != null) {
          settle();
        } else if (nextPartNumber > partCount && running == 0) {
          complete();
        } else {
          launch();
        }
      });
    }
  }

  /**
   * Hashes the range if needed, then streams it from its own handle, closed when the request is
   * done; part {@code 0} is the single PutObject. The Future gives the ETag.
   */
  private Future<String> sendRange(int partNumber, long offset, long length) {
    Future<String> hash = hashParts ? hashRange(offset, length) : caller.succeeded(S3SignatureV4.UNSIGNED_PAYLOAD);
    return hash.compose(payloadHash -> {
      if (failure != null) {
        return caller.<String>failed(failure);
      }
      return open.apply(filename).compose(file -> {
        file.pause();
        file.setReadPos(offset);
        file.setReadLength(length);
        Transport.StreamedExchange exchange = partNumber == 0
          ? client.putStreamed(caller, bucket, key, headers, file, length, payloadHash)
          : client.uploadPartStreamed(caller, bucket, key, uploadId, partNumber, file, length, payloadHash);
        exchanges.put(partNumber, exchange);
        if (failure != null) {
          exchange.reset(failure);
        }
        return exchange.response
          .compose(response -> {
            String etag = response.headers.get(HttpHeaders.ETAG);
            return etag != null || partNumber == 0 ? caller.succeeded(etag)
              : caller.<String>failed(new S3ClientException("UploadPart response without an ETag (part " + partNumber
                + ")", null));
          })
          .transform(ar -> {
            exchanges.remove(partNumber);
            return file.close().transform(closed -> outcome(ar));
          });
      });
    });
  }

  /** {@code ar} as a Future bound to the caller's context. */
  private <T> Future<T> outcome(io.vertx.core.AsyncResult<T> ar) {
    return ar.succeeded() ? caller.succeeded(ar.result()) : caller.failed(ar.cause());
  }

  /** The SHA-256 (hex) of a range, read through its own handle, which is then closed. */
  private Future<String> hashRange(long offset, long length) {
    return open.apply(filename).compose(file -> {
      Promise<String> hex = caller.promise();
      MessageDigest digest = HashUtil.sha256();
      long[] read = {0};
      file.setReadPos(offset);
      file.setReadLength(length);
      file.exceptionHandler(hex::tryFail);
      file.endHandler(v -> {
        if (read[0] == length) {
          hex.tryComplete(HashUtil.hex(digest.digest()));
        } else {
          hex.tryFail(new S3ClientException("The file changed during the upload: " + length + " bytes expected at offset "
            + offset + ", " + read[0] + " read", null));
        }
      });
      file.handler(chunk -> {
        read[0] += chunk.length();
        digest.update(chunk.getBytes());
      });
      return hex.future().transform(ar -> file.close().transform(closed -> outcome(ar)));
    });
  }

  private void complete() {
    finishing = true;
    client.completeUpload(caller, bucket, key, uploadId, parts).onComplete(ar -> {
      if (ar.succeeded()) {
        client.unregister(this);
        result.tryComplete(ar.result());
      } else {
        failure = ar.cause();
        abortAndFail();
      }
    });
  }

  /** Records the first failure and resets the requests in flight; the abort follows once they settle. */
  private void fail(Throwable cause) {
    if (failure != null || finishing) {
      return;
    }
    failure = cause;
    for (Transport.StreamedExchange exchange : new ArrayList<>(exchanges.values())) {
      exchange.reset(cause);
    }
    settle();
  }

  /** Aborts once nothing is in flight any more (the single PUT reports its own outcome). */
  private void settle() {
    if (running == 0 && !finishing && partCount > 1) {
      abortAndFail();
    }
  }

  private void abortAndFail() {
    finishing = true;
    if (uploadId == null) {
      client.unregister(this);
      result.tryFail(failure);
      return;
    }
    client.abortUpload(caller, bucket, key, uploadId).onComplete(ar -> {
      if (ar.failed() && ar.cause() != failure) {
        failure.addSuppressed(ar.cause());
      }
      client.unregister(this);
      result.tryFail(failure);
    });
  }
}
