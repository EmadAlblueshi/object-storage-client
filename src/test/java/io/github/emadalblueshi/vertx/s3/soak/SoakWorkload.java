package io.github.emadalblueshi.vertx.s3.soak;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignUploadPartOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.streams.ReadStream;

/**
 * The soak's operations, picked by weight. Each verifies its outcome (digests, sizes, statuses,
 * error types) and fails on anything unexpected; an expected error is a success. Every object a
 * worker creates is deleted, and every multipart upload is completed or aborted, by the same
 * operation.
 */
final class SoakWorkload {

  enum Op {
    BUFFERED("buffered round trip", 20),
    STREAM_DOWNLOAD("streamed download", 12),
    RANGED("ranged download", 6),
    STREAM_UPLOAD("streamed upload", 8),
    FILE_UPLOAD("file upload", 4),
    PRESIGNED("presigned", 14),
    CANCEL("cancel", 8),
    LISTING("listing", 8),
    ERRORS("expected error", 10),
    MULTIPART("multipart lifecycle", 4);

    final String label;
    final int weight;

    Op(String label, int weight) {
      this.label = label;
      this.weight = weight;
    }
  }

  static final String WORKER_PREFIX = "soak/w";
  private static final int MIB = 1024 * 1024;
  private static final String FIXTURES = "soak/fixture/";
  private static final String LISTED = "soak/list/";
  private static final int LISTED_KEYS = 50;
  private static final int[] FIXTURE_SIZES = {1024, 4 * MIB, 24 * MIB};
  private static final int[] BUFFERED_SIZES = {0, 1, 1024, 64 * 1024, 256 * 1024, MIB};
  private static final int[] STREAMED_SIZES = {0, 1, 1024, 8 * MIB - 1, 8 * MIB + 1, 17 * MIB, 24 * MIB};
  private static final int[] FILE_SIZES = {1024, 9 * MIB, 17 * MIB};
  private static final int[] PRESIGNED_SIZES = {0, 1024, 64 * 1024, MIB};
  private static final String SOURCE_FAILURE = "soak: the source failed on purpose";

  private final Vertx vertx;
  private final S3Client client;
  private final S3Client wrongSecret;
  private final S3Client capped;
  private final S3Presigner presigner;
  private final HttpClient http;
  private final String bucket;
  /** Every object's content is a prefix (or, for buffered uploads, a slice) of these bytes. */
  private final byte[] random = new byte[25 * MIB];
  private final Map<Integer, byte[]> digests = new TreeMap<>();
  private final Map<Integer, Path> files = new TreeMap<>();
  private final int totalWeight = Arrays.stream(Op.values()).mapToInt(op -> op.weight).sum();

  SoakWorkload(Vertx vertx, S3Client client, S3Client wrongSecret, S3Client capped, HttpClient http, String bucket) {
    this.vertx = vertx;
    this.client = client;
    this.wrongSecret = wrongSecret;
    this.capped = capped;
    this.presigner = client.presigner();
    this.http = http;
    this.bucket = bucket;
    new Random(20261126).nextBytes(random);
  }

  /** Uploads the fixtures and writes the files the uploads read; blocking, before the workers start. */
  void prepare(Path directory) throws Exception {
    for (int size : FIXTURE_SIZES) {
      await(client.putObject(bucket, FIXTURES + size, prefix(size)));
      digests.put(size, sha256(random, 0, size));
    }
    for (int i = 0; i < LISTED_KEYS; i++) {
      await(client.putObject(bucket, String.format("%s%02d", LISTED, i), Buffer.buffer("x")));
    }
    for (int[] sizes : new int[][] {STREAMED_SIZES, FILE_SIZES}) {
      for (int size : sizes) {
        if (!files.containsKey(size)) {
          Path file = directory.resolve(size + ".bin");
          Files.write(file, Arrays.copyOf(random, size));
          files.put(size, file);
          digests.put(size, sha256(random, 0, size));
        }
      }
    }
  }

  Op pick(Random dice) {
    int roll = dice.nextInt(totalWeight);
    for (Op op : Op.values()) {
      roll -= op.weight;
      if (roll < 0) {
        return op;
      }
    }
    throw new IllegalStateException();
  }

  /** Runs {@code op} for a worker; the result is the number of bytes moved. Runs on the worker's context. */
  Future<Long> run(Op op, String key, Random dice) {
    switch (op) {
      case BUFFERED:
        return buffered(key, dice);
      case STREAM_DOWNLOAD:
        return streamDownload(dice);
      case RANGED:
        return ranged(dice);
      case STREAM_UPLOAD:
        return streamUpload(key, STREAMED_SIZES[dice.nextInt(STREAMED_SIZES.length)]);
      case FILE_UPLOAD:
        return fileUpload(key, FILE_SIZES[dice.nextInt(FILE_SIZES.length)]);
      case PRESIGNED:
        return presigned(key, dice);
      case CANCEL:
        return dice.nextInt(10) < 6 ? cancelDownload() : failingUpload(key);
      case LISTING:
        return listing(dice);
      case ERRORS:
        return expectedError(key, dice);
      case MULTIPART:
      default:
        return client.createMultipartUpload(bucket, key, null).compose(uploadId -> client.listParts(bucket, key, uploadId)
          .compose(parts -> check(parts.isEmpty(), "a new upload lists parts: " + parts.size())
            .eventually(() -> client.abortMultipartUpload(bucket, key, uploadId))));
    }
  }

  private Future<Long> buffered(String key, Random dice) {
    int size = BUFFERED_SIZES[dice.nextInt(BUFFERED_SIZES.length)];
    int offset = dice.nextInt(random.length - size + 1);
    Buffer body = Buffer.buffer(Arrays.copyOfRange(random, offset, offset + size));
    return client.putObject(bucket, key, body)
      .compose(etag -> client.getObject(bucket, key))
      .compose(downloaded -> check(downloaded.equals(body), "getObject returned other bytes than were put"))
      .compose(v -> client.headObject(bucket, key))
      .compose(head -> check(head.getContentLength() == size, "headObject size " + head.getContentLength() + ", expected " + size))
      .compose(v -> client.deleteObject(bucket, key))
      .map(2L * size);
  }

  private Future<Long> streamDownload(Random dice) {
    int size = FIXTURE_SIZES[dice.nextInt(FIXTURE_SIZES.length)];
    boolean slow = dice.nextInt(4) == 0;
    return client.getObjectStream(bucket, FIXTURES + size).compose(stream -> consume(stream, size, slow));
  }

  private Future<Long> ranged(Random dice) {
    int size = FIXTURE_SIZES[1 + dice.nextInt(2)];
    int start = dice.nextInt(size);
    int end = start + dice.nextInt(Math.min(MIB, size - start));
    return client.getObjectStream(bucket, FIXTURES + size, "bytes=" + start + "-" + end)
      .compose(stream -> collect(stream))
      .compose(bytes -> check(bytes.equals(Buffer.buffer(Arrays.copyOfRange(random, start, end + 1))),
        "range " + start + "-" + end + " returned other bytes"))
      .map((long) end - start + 1);
  }

  private Future<Long> streamUpload(String key, int size) {
    return openFile(size)
      .compose(file -> client.putObject(bucket, key, file).eventually(file::close))
      .compose(etag -> verifyAndDelete(key, size));
  }

  private Future<Long> fileUpload(String key, int size) {
    return client.putFile(bucket, key, files.get(size).toString()).compose(etag -> verifyAndDelete(key, size));
  }

  private Future<Long> presigned(String key, Random dice) {
    int kind = dice.nextInt(10);
    if (kind < 6) {
      int size = PRESIGNED_SIZES[dice.nextInt(PRESIGNED_SIZES.length)];
      Buffer body = prefix(size);
      S3PresignedRequest put = presigner.presignPut(bucket, key, 5, TimeUnit.MINUTES, new S3PresignPutOptions()
        .setContentType("application/octet-stream").setContentMd5(md5(body)).setContentLength((long) size));
      return send(HttpMethod.PUT, put.getUrl(), put.getHeaders(), body)
        .compose(uploaded -> check(uploaded.status == 200, "presigned PUT answered " + uploaded.status))
        .compose(v -> send(HttpMethod.GET, presigner.presignGet(bucket, key, 5, TimeUnit.MINUTES), Map.of(), null))
        .compose(downloaded -> check(downloaded.status == 200 && downloaded.body.equals(body),
          "presigned GET answered " + downloaded.status + " with " + downloaded.body.length() + " bytes"))
        .compose(v -> client.deleteObject(bucket, key))
        .map(2L * size);
    }
    if (kind < 8) {
      return client.createMultipartUpload(bucket, key, null).compose(uploadId -> presignedParts(key, uploadId)
        .recover(e -> client.abortMultipartUpload(bucket, key, uploadId).transform(abort -> Future.failedFuture(e))))
        .compose(etag -> client.headObject(bucket, key))
        .compose(head -> check(head.getContentLength() == 5L * MIB + 1024, "the parts made " + head.getContentLength() + " bytes"))
        .compose(v -> client.deleteObject(bucket, key))
        .map(5L * MIB + 1024);
    }
    // A body one byte longer than the signed Content-Length is refused, and nothing is stored
    S3PresignedRequest put = presigner.presignPut(bucket, key, 5, TimeUnit.MINUTES,
      new S3PresignPutOptions().setContentLength(1024L));
    return send(HttpMethod.PUT, put.getUrl(), Map.of(), prefix(1025))
      .compose(refused -> check(refused.status == 403, "a body longer than its signed length answered " + refused.status))
      .compose(v -> expectFailure(client.headObject(bucket, key), S3ServiceException.class, 404));
  }

  private Future<String> presignedParts(String key, String uploadId) {
    Buffer first = prefix(5 * MIB);
    Buffer second = Buffer.buffer(Arrays.copyOfRange(random, 5 * MIB, 5 * MIB + 1024));
    S3PresignedRequest part1 = presigner.presignUploadPart(bucket, key, uploadId, 1, 5, TimeUnit.MINUTES,
      new S3PresignUploadPartOptions().setContentLength(5L * MIB));
    S3PresignedRequest part2 = presigner.presignUploadPart(bucket, key, uploadId, 2, 5, TimeUnit.MINUTES,
      new S3PresignUploadPartOptions().setContentLength(1024L));
    return send(HttpMethod.PUT, part1.getUrl(), part1.getHeaders(), first).compose(r1 ->
      send(HttpMethod.PUT, part2.getUrl(), part2.getHeaders(), second).compose(r2 ->
        check(r1.status == 200 && r2.status == 200, "presigned parts answered " + r1.status + " and " + r2.status)
          .compose(v -> client.completeMultipartUpload(bucket, key, uploadId,
            List.of(new S3CompletedPart(1, r1.etag), new S3CompletedPart(2, r2.etag))))));
  }

  /** A 24 MiB object stream cancelled after its first chunk: it fails with an S3ClientException. */
  private Future<Long> cancelDownload() {
    return client.getObjectStream(bucket, FIXTURES + 24 * MIB).compose(stream -> {
      Promise<Long> done = Promise.promise();
      boolean[] cancelled = {false};
      stream.exceptionHandler(e -> {
        if (e instanceof S3ClientException) {
          done.tryComplete(0L);
        } else {
          done.tryFail(e);
        }
      });
      stream.endHandler(v -> done.tryFail(new AssertionError("a cancelled stream ended")));
      stream.handler(chunk -> {
        if (!cancelled[0]) {
          cancelled[0] = true;
          stream.cancel();
        }
      });
      stream.resume();
      return done.future();
    });
  }

  /** A streamed upload whose source fails after 9 MiB, i.e. during the multipart upload: it must be aborted. */
  private Future<Long> failingUpload(String key) {
    return openFile(24 * MIB).compose(file -> client.putObject(bucket, key, new FailingStream(file, 9L * MIB))
      .transform(outcome -> {
        Future<Void> closed = file.close();
        if (outcome.succeeded()) {
          return closed.compose(v -> Future.failedFuture(new AssertionError("an upload whose source failed succeeded")));
        }
        for (Throwable cause = outcome.cause(); cause != null; cause = cause.getCause()) {
          if (SOURCE_FAILURE.equals(cause.getMessage())) {
            return closed.map(9L * MIB);
          }
        }
        return closed.compose(v -> Future.failedFuture(outcome.cause()));
      }));
  }

  private Future<Long> listing(Random dice) {
    int kind = dice.nextInt(3);
    if (kind == 0) {
      return listAll(null, 0).compose(count -> check(count == LISTED_KEYS, "listing found " + count + " keys"));
    }
    if (kind == 1) {
      return client.listBuckets().compose(result -> check(result.getBuckets().stream()
        .anyMatch(b -> bucket.equals(b.getName())), "listBuckets is missing the bucket"));
    }
    return client.listMultipartUploads(bucket, null).map(0L);
  }

  private Future<Long> listAll(String token, int found) {
    S3ListObjectsOptions options = new S3ListObjectsOptions().setPrefix(LISTED).setMaxKeys(10);
    if (token != null) {
      options.setContinuationToken(token);
    }
    return client.listObjects(bucket, options).compose(page -> {
      int count = found + page.getObjects().size();
      return page.isTruncated() ? listAll(page.getNextContinuationToken(), count) : Future.succeededFuture((long) count);
    });
  }

  private Future<Long> expectedError(String key, Random dice) {
    switch (dice.nextInt(6)) {
      case 0:
        return expectFailure(client.getObject(bucket, key + "-missing"), S3ServiceException.class, 404);
      case 1:
        return expectFailure(client.headObject(bucket, key + "-missing"), S3ServiceException.class, 404);
      case 2:
        return expectFailure(wrongSecret.getObject(bucket, FIXTURES + 1024), S3ServiceException.class, 403);
      case 3:
        return expectFailure(capped.getObject(bucket, FIXTURES + 1024), S3ClientException.class, 0);
      case 4:
        return expectFailure(client.getObject(bucket, ""), S3ClientException.class, 0);
      default:
        return client.createMultipartUpload(bucket, key, null).compose(uploadId -> expectFailure(
          client.completeMultipartUpload(bucket, key, uploadId, List.of(new S3CompletedPart(1, "\"0000000000000000\""))),
          S3ServiceException.class, 400)
          .transform(outcome -> client.abortMultipartUpload(bucket, key, uploadId)
            .compose(v -> outcome.succeeded() ? Future.succeededFuture(0L) : Future.failedFuture(outcome.cause()))));
    }
  }

  private Future<Long> verifyAndDelete(String key, int size) {
    return client.getObjectStream(bucket, key)
      .compose(stream -> consume(stream, size, false))
      .compose(downloaded -> client.deleteObject(bucket, key))
      .map(2L * size);
  }

  /** Reads a stream to its end, checking its size and digest; a slow consumer pauses after each chunk. */
  private Future<Long> consume(S3ObjectStream stream, int size, boolean slow) {
    Promise<Long> done = Promise.promise();
    MessageDigest digest = sha256();
    long[] count = {0};
    stream.exceptionHandler(done::tryFail);
    stream.endHandler(v -> {
      if (count[0] == size && MessageDigest.isEqual(digest.digest(), digests.get(size))) {
        done.tryComplete(count[0]);
      } else {
        done.tryFail(new AssertionError("read " + count[0] + " bytes (expected " + size + ") or other content"));
      }
    });
    stream.handler(chunk -> {
      digest.update(chunk.getBytes());
      count[0] += chunk.length();
      if (slow) {
        stream.pause();
        vertx.setTimer(1, id -> stream.resume());
      }
    });
    stream.resume();
    return done.future();
  }

  private Future<Buffer> collect(S3ObjectStream stream) {
    Promise<Buffer> done = Promise.promise();
    Buffer bytes = Buffer.buffer();
    stream.exceptionHandler(done::tryFail);
    stream.endHandler(v -> done.tryComplete(bytes));
    stream.handler(bytes::appendBuffer);
    stream.resume();
    return done.future();
  }

  private Future<AsyncFile> openFile(int size) {
    return vertx.fileSystem().open(files.get(size).toString(), new OpenOptions().setRead(true).setWrite(false).setCreate(false))
      .map(file -> file.setReadBufferSize(64 * 1024));
  }

  private Future<Response> send(HttpMethod method, String url, Map<String, String> headers, Buffer body) {
    RequestOptions options = new RequestOptions().setMethod(method).setAbsoluteURI(url);
    headers.forEach(options::putHeader);
    return http.request(options)
      .compose(request -> body == null ? request.send() : request.send(body))
      .compose(response -> response.body().map(content -> new Response(response.statusCode(), response.getHeader("ETag"), content)));
  }

  private Buffer prefix(int size) {
    return Buffer.buffer(Arrays.copyOf(random, size));
  }

  private static Future<Long> check(boolean condition, String otherwise) {
    return condition ? Future.succeededFuture(0L) : Future.failedFuture(new AssertionError(otherwise));
  }

  private static Future<Long> expectFailure(Future<?> operation, Class<? extends Throwable> type, int status) {
    return operation.transform(outcome -> {
      if (outcome.succeeded()) {
        return Future.failedFuture(new AssertionError("expected a " + type.getSimpleName() + ", got a success"));
      }
      Throwable error = outcome.cause();
      boolean matches = type.isInstance(error)
        && (status == 0 || error instanceof S3ServiceException && ((S3ServiceException) error).getStatusCode() == status);
      return matches ? Future.succeededFuture(0L)
        : Future.failedFuture(new AssertionError("expected a " + type.getSimpleName() + (status == 0 ? "" : " " + status)
          + ", got " + error));
    });
  }

  private static String md5(Buffer body) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.getBytes()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] bytes, int offset, int length) {
    MessageDigest digest = sha256();
    digest.update(bytes, offset, length);
    return digest.digest();
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.MINUTES);
  }

  private static final class Response {
    final int status;
    final String etag;
    final Buffer body;

    Response(int status, String etag, Buffer body) {
      this.status = status;
      this.etag = etag;
      this.body = body;
    }
  }

  /** A file's stream that fails, instead of delivering more, once {@code failAfter} bytes have passed. */
  private static final class FailingStream implements ReadStream<Buffer> {
    private final AsyncFile file;
    private final long failAfter;
    private long delivered;
    private boolean failed;
    private Handler<Throwable> exceptionHandler;

    FailingStream(AsyncFile file, long failAfter) {
      this.file = file;
      this.failAfter = failAfter;
    }

    @Override
    public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
      exceptionHandler = handler;
      file.exceptionHandler(handler);
      return this;
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
      if (handler == null) {
        file.handler(null);
        return this;
      }
      file.handler(chunk -> {
        if (failed) {
          return;
        }
        delivered += chunk.length();
        if (delivered > failAfter) {
          failed = true;
          file.pause();
          Handler<Throwable> onFailure = exceptionHandler;
          if (onFailure != null) {
            onFailure.handle(new IOException(SOURCE_FAILURE));
          }
          return;
        }
        handler.handle(chunk);
      });
      return this;
    }

    @Override
    public ReadStream<Buffer> pause() {
      file.pause();
      return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
      if (!failed) {
        file.resume();
      }
      return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
      if (!failed) {
        file.fetch(amount);
      }
      return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
      file.endHandler(endHandler);
      return this;
    }
  }
}
