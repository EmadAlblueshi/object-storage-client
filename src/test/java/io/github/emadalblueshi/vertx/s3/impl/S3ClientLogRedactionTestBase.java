package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.NettyLeakCheck;
import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignUploadPartOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * The log-redaction audit against one {@link S3TestBackend} per subclass. Everything logged, by any
 * logger at any level, is captured while the client runs the main operations (buffered and
 * streamed uploads and downloads, a cancelled stream, listings, multipart uploads, presigned
 * uploads and downloads) and while it fails (a wrong secret key, a session token the server doesn't
 * know, a missing bucket and key, the size cap, a closed client, invalid arguments). Neither the
 * log nor any exception may contain the secret key, the session token or a presigned URL's
 * signature; the log not even the access key or text that only appears where credentials are.
 * {@code LogRedactionStubTest} holds the control: with {@code logActivity} on, the same capture
 * sees the credentials.
 * <p>
 * Safe on a real account: one uniquely named bucket, every key registered before it is written,
 * cleanup even when tests fail.
 */
@ExtendWith(NettyLeakCheck.class)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientLogRedactionTestBase {

  /** S3's smallest part: a streamed upload of a part and a byte is a multipart upload of two parts. */
  private static final int MIN_PART = 5 * 1024 * 1024;
  private static final Pattern PRESIGNED_SIGNATURE = Pattern.compile("[?&]X-Amz-Signature=([0-9a-f]+)");

  protected Vertx vertx;
  protected S3TestBackend backend;
  protected S3Client client;
  protected final TestResources resources = new TestResources();
  protected final String bucket = resources.bucket(TestResources.uniqueBucketName());
  private Context context;
  private HttpClient http;
  private VertxLogCapture log;
  /** The signatures of the presigned URLs this test created: they must never be logged. */
  private final List<String> presignedSignatures = new CopyOnWriteArrayList<>();

  /** The backend for this suite; may abort the class (skipped) if it isn't available. */
  protected abstract S3TestBackend backend();

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = backend();
    this.context = vertx.getOrCreateContext();
    this.client = S3Client.create(vertx, TestResources.clientOptions(backend));
    this.http = vertx.createHttpClient(backend.httpClientOptions());
    client.createBucket(bucket).onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    if (client == null) {
      testContext.completeNow();
      return;
    }
    client.listMultipartUploads(bucket, null)
      .compose(open -> Future.join(open.stream()
        .map(upload -> client.abortMultipartUpload(bucket, upload.getKey(), upload.getUploadId()))
        .collect(Collectors.toList())))
      .otherwiseEmpty()
      .compose(v -> resources.cleanUp(client))
      .eventually(() -> http.close())
      .eventually(client::close)
      .onComplete(testContext.succeedingThenComplete());
  }

  @BeforeEach
  void startCapture() {
    presignedSignatures.clear();
    log = VertxLogCapture.everything();
  }

  @AfterEach
  void stopCapture() {
    log.close();
  }

  /** The main operations, presigning included, log nothing secret; a cancelled stream's failure carries nothing secret. */
  @Test
  void mainOperationsLogNothingSecret(VertxTestContext testContext) {
    String key = resources.key(bucket, "redaction/object.bin");
    Buffer data = Buffer.buffer(random(1024));
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    client.headBucket(bucket)
      .compose(v -> client.putObject(bucket, key, data))
      .compose(etag -> client.getObject(bucket, key))
      .compose(body -> client.headObject(bucket, key))
      .compose(head -> client.getObjectStream(bucket, key))
      .compose(stream -> stream.collect(Collectors.toList()))
      .compose(chunks -> client.getObjectStream(bucket, key))
      .compose(stream -> {
        Promise<Throwable> cancelled = Promise.promise();
        stream.exceptionHandler(cancelled::tryComplete);
        return stream.cancel().compose(v -> cancelled.future()).map(failures::add);
      })
      .compose(added -> client.listObjects(bucket, new S3ListObjectsOptions().setPrefix("redaction/")))
      .compose(page -> streamedUpload(resources.key(bucket, "redaction/streamed.bin")))
      .compose(etag -> fileUpload(resources.key(bucket, "redaction/file.bin")))
      .compose(etag -> presignedPutAndGet(resources.key(bucket, "redaction/presigned.bin"), data))
      .compose(v -> presignedMultipartUpload(resources.key(bucket, "redaction/parts.bin")))
      .compose(etag -> abortedUpload(resources.key(bucket, "redaction/aborted.bin")))
      .compose(v -> client.listMultipartUploads(bucket, "redaction/"))
      .compose(open -> client.deleteObject(bucket, key))
      .compose(v -> client.listBuckets())
      .onComplete(testContext.succeeding(buckets -> testContext.verify(() -> {
        assertEquals(1, failures.size(), "the cancelled stream's failure");
        assertInstanceOf(S3ClientException.class, failures.get(0));
        assertEquals(3, presignedSignatures.size(), "presigned URLs were used");
        assertNothingSecret(failures, List.of());
        testContext.completeNow();
      })));
  }

  /** What a misconfigured or expired client meets: neither the failures (as a log shows them) nor the log contain a secret. */
  @Test
  void failuresCarryAndLogNothingSecret(VertxTestContext testContext) {
    String key = resources.key(bucket, "redaction/failures.bin");
    String wrongSecret = "wrong" + UUID.randomUUID().toString().replace("-", "");
    String token = "FQoGZXIvYXdzEtestOnly" + UUID.randomUUID().toString().replace("-", "");
    S3Client wrongSecretKey = S3Client.create(vertx, TestResources.clientOptions(backend)
      .setCredentials(new S3Credentials(backend.accessKey(), wrongSecret)));
    S3Client unknownToken = S3Client.create(vertx, TestResources.clientOptions(backend)
      .setCredentials(new S3Credentials(backend.accessKey(), backend.secretKey()).setSessionToken(token)));
    S3Client capped = S3Client.create(vertx, TestResources.clientOptions(backend).setMaxBufferedObjectSize(16));
    S3Client closed = S3Client.create(vertx, TestResources.clientOptions(backend));
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    client.putObject(bucket, key, Buffer.buffer(random(1024)))
      .compose(etag -> expectServiceError(failures, wrongSecretKey.getObject(bucket, key), 403))
      .compose(v -> expectServiceError(failures, wrongSecretKey.headObject(bucket, key), 403))
      .compose(v -> expectServiceError(failures, wrongSecretKey.putObject(bucket,
        resources.key(bucket, "redaction/never.bin"), Buffer.buffer("x")), 403))
      // The server may reject the token or ignore it: either way nothing may leak
      .compose(v -> unknownToken.getObject(bucket, key).<Void>transform(outcome -> {
        if (outcome.failed()) {
          failures.add(outcome.cause());
        }
        return Future.succeededFuture();
      }))
      .compose(v -> expectServiceError(failures, client.getObject(TestResources.uniqueBucketName(), "k"), -1))
      .compose(v -> expectServiceError(failures, client.getObject(bucket, "redaction/missing.bin"), 404))
      .compose(v -> expectServiceError(failures, client.headObject(bucket, "redaction/missing.bin"), 404))
      .compose(v -> expectClientError(failures, capped.getObject(bucket, key)))
      .compose(v -> closed.close())
      .compose(v -> expectClientError(failures, closed.headBucket(bucket)))
      .compose(v -> expectClientError(failures, client.headBucket("Not_A_Bucket")))
      .compose(v -> expectClientError(failures, client.getObject(bucket, "")))
      .compose(v -> invalidPresigning(failures))
      .eventually(() -> Future.join(wrongSecretKey.close(), unknownToken.close(), capped.close()))
      .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
        assertNothingSecret(failures, List.of(wrongSecret, token));
        testContext.completeNow();
      })));
  }

  /**
   * Neither the log nor an exception contains the secret key, a test's {@code otherSecrets}, or a
   * presigned URL's signature or query parameters; the log not even the access key or text that
   * only appears where credentials are ({@link Leaks#LOG_MARKERS}).
   */
  private void assertNothingSecret(List<Throwable> failures, List<String> otherSecrets) {
    List<String> secrets = new ArrayList<>(otherSecrets);
    secrets.add(backend.secretKey());
    secrets.addAll(presignedSignatures);
    for (Throwable failure : failures) {
      Leaks.assertNoLeak("a " + failure.getClass().getSimpleName(), Leaks.asLogged(failure), secrets,
        Leaks.PRESIGNED_QUERY_MARKERS);
    }
    List<String> notLogged = new ArrayList<>(secrets);
    notLogged.add(backend.accessKey());
    Leaks.assertNoLeak("the log", log.logged(), notLogged, Leaks.LOG_MARKERS);
  }

  /** A streamed upload of a part and a byte (two parts, through the package-private hook) from a file. */
  private Future<String> streamedUpload(String key) {
    return tempFile(random(MIN_PART + 1))
      .compose(path -> vertx.fileSystem().open(path, new OpenOptions().setRead(true).setWrite(false).setCreate(false)))
      .compose(file -> ((S3ClientImpl) client).putObject(bucket, key, file, null, MIN_PART, MultipartRequests.MAX_PARTS)
        .eventually(file::close));
  }

  private Future<String> fileUpload(String key) {
    return tempFile(random(1024)).compose(path -> client.putFile(bucket, key, path));
  }

  /** An upload (with signed headers) and a download through presigned URLs, with a plain HttpClient, as an app would. */
  private Future<Void> presignedPutAndGet(String key, Buffer data) {
    S3Presigner presigner = client.presigner();
    S3PresignedRequest put = presigner.presignPut(bucket, key, 10, TimeUnit.MINUTES, new S3PresignPutOptions()
      .setContentType("application/octet-stream").setContentLength((long) data.length()));
    String get = presigner.presignGet(bucket, key, 10, TimeUnit.MINUTES);
    remember(put.getUrl());
    remember(get);
    return send(HttpMethod.PUT, put.getUrl(), put.getHeaders(), data)
      .compose(uploaded -> expectOk("the presigned upload", uploaded))
      .compose(v -> send(HttpMethod.GET, get, Map.of(), null))
      .compose(downloaded -> expectOk("the presigned download", downloaded))
      .mapEmpty();
  }

  /** A multipart upload of one part through a presigned part URL, with a listing of the parts. */
  private Future<String> presignedMultipartUpload(String key) {
    Buffer part = Buffer.buffer(random(1024));
    return client.createMultipartUpload(bucket, key, null).compose(uploadId -> {
      S3PresignedRequest request = client.presigner().presignUploadPart(bucket, key, uploadId, 1, 10, TimeUnit.MINUTES,
        new S3PresignUploadPartOptions().setContentLength((long) part.length()));
      remember(request.getUrl());
      return send(HttpMethod.PUT, request.getUrl(), request.getHeaders(), part)
        .compose(uploaded -> expectOk("the presigned part upload", uploaded))
        .compose(uploaded -> client.listParts(bucket, key, uploadId).map(parts -> uploaded.headers.get("ETag")))
        .compose(etag -> client.completeMultipartUpload(bucket, key, uploadId, List.of(new S3CompletedPart(1, etag))));
    });
  }

  private Future<Void> abortedUpload(String key) {
    return client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> client.abortMultipartUpload(bucket, key, uploadId));
  }

  /** Invalid presigning arguments throw; the messages are checked like every other failure. */
  private Future<Void> invalidPresigning(List<Throwable> failures) {
    S3Presigner presigner = client.presigner();
    List<Runnable> calls = List.of(
      () -> presigner.presignGet(bucket, "k", 0, TimeUnit.SECONDS),
      () -> presigner.presignPut(bucket, "k", 8, TimeUnit.DAYS),
      () -> presigner.presignPut(bucket, "k", 10, TimeUnit.MINUTES, new S3PresignPutOptions().setContentLength(-1L)),
      () -> presigner.presignUploadPart(bucket, "k", "upload-1", 0, 10, TimeUnit.MINUTES),
      () -> presigner.presignGet("Not_A_Bucket", "k", 10, TimeUnit.MINUTES));
    for (Runnable call : calls) {
      try {
        call.run();
        return Future.failedFuture(new AssertionError("presigning was expected to fail"));
      } catch (IllegalArgumentException e) {
        failures.add(e);
      }
    }
    return Future.succeededFuture();
  }

  /** Expects an {@link S3ServiceException} with {@code status} ({@code -1}: any) and keeps it. */
  private static Future<Void> expectServiceError(List<Throwable> failures, Future<?> operation, int status) {
    return operation.transform(outcome -> {
      if (outcome.succeeded()) {
        return Future.failedFuture(new AssertionError("expected an S3 error"));
      }
      failures.add(outcome.cause());
      if (!(outcome.cause() instanceof S3ServiceException)
        || status != -1 && ((S3ServiceException) outcome.cause()).getStatusCode() != status) {
        // The exception itself isn't shown: against a real account it could be what leaks
        return Future.failedFuture(new AssertionError("expected an S3ServiceException with status " + status + ", got a "
          + outcome.cause().getClass().getSimpleName() + (outcome.cause() instanceof S3ServiceException
          ? " with status " + ((S3ServiceException) outcome.cause()).getStatusCode() : "")));
      }
      return Future.succeededFuture();
    });
  }

  /** Expects an {@link S3ClientException} and keeps it. */
  private static Future<Void> expectClientError(List<Throwable> failures, Future<?> operation) {
    return operation.transform(outcome -> {
      if (outcome.succeeded()) {
        return Future.failedFuture(new AssertionError("expected a client error"));
      }
      failures.add(outcome.cause());
      return outcome.cause() instanceof S3ClientException ? Future.succeededFuture()
        : Future.failedFuture(new AssertionError("expected an S3ClientException, got a "
          + outcome.cause().getClass().getSimpleName()));
    });
  }

  /** Keeps the signature of a presigned URL, which must never be logged. */
  private void remember(String url) {
    Matcher signature = PRESIGNED_SIGNATURE.matcher(url);
    if (!signature.find()) {
      throw new AssertionError("a presigned URL without a signature");
    }
    presignedSignatures.add(signature.group(1));
  }

  /** Sends a request to a presigned URL, exactly as returned, with a plain HttpClient, on one context. */
  private Future<Response> send(HttpMethod method, String url, Map<String, String> headers, Buffer body) {
    RequestOptions options = new RequestOptions().setMethod(method).setAbsoluteURI(url);
    headers.forEach(options::putHeader);
    Promise<Response> promise = Promise.promise();
    context.runOnContext(v -> http.request(options)
      .compose(request -> body == null ? request.send() : request.send(body))
      .compose(response -> response.body().map(content -> new Response(response.statusCode(), response.headers())))
      .onComplete(promise));
    return promise.future();
  }

  /** Fails unless {@code response} is a 200; the body isn't shown, since a presigned request's error may echo its query. */
  private static Future<Response> expectOk(String what, Response response) {
    return response.status == 200 ? Future.succeededFuture(response)
      : Future.failedFuture(new AssertionError(what + " answered " + response.status));
  }

  /** A temporary file with {@code content}, deleted when the JVM exits. */
  private Future<String> tempFile(byte[] content) {
    return vertx.fileSystem().createTempFile("vertx-s3-it-", ".bin").compose(path -> {
      new java.io.File(path).deleteOnExit();
      return vertx.fileSystem().writeFile(path, Buffer.buffer(content)).map(path);
    });
  }

  private static byte[] random(int size) {
    byte[] data = new byte[size];
    new Random(size).nextBytes(data);
    return data;
  }

  private static final class Response {
    final int status;
    final MultiMap headers;

    Response(int status, MultiMap headers) {
      this.status = status;
      this.headers = headers;
    }
  }
}
