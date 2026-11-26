package io.github.emadalblueshi.vertx.s3.impl;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * An upload from a {@link ReadStream} of unknown length: a single PutObject when the stream ends
 * within the first part, otherwise a multipart upload through the client's internal operations.
 * <ul>
 *   <li>The source is paused by the caller before the upload starts; its events are handled on the
 *   caller's context (inline when the source runs there, otherwise handed over in order), where all
 *   the state below lives.</li>
 *   <li>A full part is shipped only when the next byte (or the end) arrives, so the last part is
 *   never empty. One part uploads while the next one fills; when that one is full too, the source
 *   is paused until the upload finishes. At most two parts (plus the chunks that arrive after the
 *   pause) are buffered.</li>
 *   <li>Over plain HTTP each part is hashed incrementally as its chunks arrive; over HTTPS nothing
 *   is hashed ({@code UNSIGNED-PAYLOAD}).</li>
 *   <li>On any failure (source, part, completion, the part limit, or the client closing) the
 *   source is paused, an in-flight request is allowed to settle, the multipart upload is aborted,
 *   and the result fails with the original cause; a failed abort is attached as suppressed.</li>
 * </ul>
 */
final class StreamingUpload implements ActiveUpload {

  private final S3ClientImpl client;
  private final ContextSupport.Caller caller;
  private final String bucket;
  private final String key;
  private final List<Map.Entry<String, String>> headers;
  private final ReadStream<Buffer> source;
  private final int partSize;
  private final int maxParts;
  private final boolean hashParts;
  private final Promise<String> result;

  // State, only touched on the caller's context
  private Buffer current;
  private MessageDigest digest;
  private final Deque<Buffer> backlog = new ArrayDeque<>();
  private final List<S3CompletedPart> parts = new ArrayList<>();
  private String uploadId;
  private int nextPartNumber = 1;
  /** A create, part upload, single PUT or completion is in flight. */
  private boolean busy;
  private boolean sourcePaused = true;
  private boolean ended;
  /** The cause once the upload has failed; it is then aborted when nothing is in flight. */
  private Throwable failure;
  /** The result is being (or has been) completed. */
  private boolean finishing;

  StreamingUpload(S3ClientImpl client, ContextSupport.Caller caller, String bucket, String key,
                  List<Map.Entry<String, String>> headers, ReadStream<Buffer> source, int partSize, int maxParts,
                  boolean hashParts) {
    this.client = client;
    this.caller = caller;
    this.bucket = bucket;
    this.key = key;
    this.headers = headers;
    this.source = source;
    this.partSize = partSize;
    this.maxParts = maxParts;
    this.hashParts = hashParts;
    this.result = caller.promise();
  }

  /** Starts reading the source, which the caller has already paused. */
  @Override
  public Future<String> start() {
    source.handler(chunk -> caller.execute(() -> onChunk(chunk)));
    source.exceptionHandler(cause -> caller.execute(() -> fail(cause)));
    source.endHandler(v -> caller.execute(this::onEnd));
    caller.execute(this::resumeSource);
    return result.future();
  }

  /**
   * Fails the upload because the client is closing; the Future completes once the upload is
   * aborted (or finished anyway).
   */
  @Override
  public Future<Void> abortOnClose() {
    caller.execute(() -> fail(new S3ClientException("The S3 client was closed during the upload", null)));
    return result.future().transform(ar -> caller.succeeded(null));
  }

  private void onChunk(Buffer chunk) {
    if (failure != null || finishing || ended) {
      return;
    }
    backlog.add(chunk);
    drain();
  }

  private void onEnd() {
    if (failure != null || finishing || ended) {
      return;
    }
    ended = true;
    finishIfIdle();
  }

  /** Moves the backlog into parts, shipping full parts while no other request is in flight. */
  private void drain() {
    while (!backlog.isEmpty() && failure == null) {
      if (current != null && current.length() == partSize) {
        // A full part, and more bytes follow: it isn't the last part
        if (busy) {
          pauseSource();
          return;
        }
        ship(false);
        if (failure != null) {
          return;
        }
        continue;
      }
      if (current == null) {
        current = Buffer.buffer();
        digest = hashParts ? HashUtil.sha256() : null;
      }
      Buffer chunk = backlog.poll();
      int room = partSize - current.length();
      if (chunk.length() > room) {
        backlog.addFirst(chunk.slice(room, chunk.length()));
        chunk = chunk.slice(0, room);
      }
      current.appendBuffer(chunk);
      if (digest != null) {
        digest.update(chunk.getBytes());
      }
    }
    if (failure == null && !ended) {
      resumeSource();
    }
  }

  /** Ships the current part: the multipart upload is created first if needed. */
  private void ship(boolean last) {
    if (nextPartNumber > maxParts) {
      fail(new S3ClientException("A streamed upload is limited to " + maxParts + " parts of " + partSize
        + " bytes (" + ((long) maxParts * partSize) + " bytes); upload a larger file with putFile", null));
      return;
    }
    int partNumber = nextPartNumber++;
    Buffer body = current;
    String hash = digest == null ? null : HashUtil.hex(digest.digest());
    current = null;
    digest = null;
    busy = true;
    Future<String> id = uploadId != null ? caller.succeeded(uploadId)
      : client.createUpload(caller, bucket, key, headers).map(created -> uploadId = created);
    id.compose(created -> failure != null ? caller.<String>failed(failure)
        : client.uploadPart(caller, bucket, key, created, partNumber, body, hash))
      .onComplete(ar -> {
        busy = false;
        if (ar.succeeded()) {
          parts.add(new S3CompletedPart(partNumber, ar.result()));
        } else if (failure == null) {
          failure = ar.cause();
        }
        if (failure != null) {
          abortAndFail();
        } else if (last) {
          complete();
        } else {
          drain();
          finishIfIdle();
        }
      });
  }

  /** Once the source has ended and nothing is pending: one PutObject, or the last part and completion. */
  private void finishIfIdle() {
    if (!ended || busy || !backlog.isEmpty() || failure != null || finishing) {
      return;
    }
    if (uploadId == null && nextPartNumber == 1) {
      finishing = true;
      busy = true;
      Buffer body = current == null ? Buffer.buffer() : current;
      String hash = digest == null ? null : HashUtil.hex(digest.digest());
      current = null;
      digest = null;
      client.putBuffered(caller, bucket, key, headers, body, hash).onComplete(ar -> {
        busy = false;
        client.unregister(this);
        if (ar.succeeded()) {
          result.tryComplete(ar.result());
        } else {
          result.tryFail(ar.cause());
        }
      });
    } else if (current != null) {
      ship(true);
    } else {
      complete();
    }
  }

  private void complete() {
    finishing = true;
    busy = true;
    client.completeUpload(caller, bucket, key, uploadId, parts).onComplete(ar -> {
      busy = false;
      if (ar.succeeded()) {
        client.unregister(this);
        result.tryComplete(ar.result());
      } else {
        finishing = false;
        failure = ar.cause();
        abortAndFail();
      }
    });
  }

  private void fail(Throwable cause) {
    if (failure != null || finishing && !busy) {
      return;
    }
    failure = cause;
    pauseSource();
    backlog.clear();
    current = null;
    digest = null;
    if (!busy) {
      abortAndFail();
    }
  }

  /** Aborts the multipart upload, if one was created, then fails the result with the original cause. */
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

  private void pauseSource() {
    if (!sourcePaused) {
      sourcePaused = true;
      try {
        source.pause();
      } catch (RuntimeException e) {
        // best effort: the upload is failing or waiting anyway
      }
    }
  }

  private void resumeSource() {
    if (sourcePaused) {
      sourcePaused = false;
      source.resume();
    }
  }
}
