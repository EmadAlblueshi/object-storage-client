package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Streamed uploads against a local HTTP stub, with 4-byte parts and a {@link ScriptedReadStream}
 * source on its own context: which requests are sent (single PutObject or multipart), when the
 * source is paused and resumed, and how failures abort the upload. The order of events is
 * controlled explicitly: the stub can withhold a reply until the test releases it, and the test
 * waits for the source to be paused or resumed.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingUploadStubTest {

  private static final String CREATED = "<InitiateMultipartUploadResult><UploadId>up-1</UploadId></InitiateMultipartUploadResult>";
  private static final String COMPLETED = "<CompleteMultipartUploadResult><ETag>\"etag-final\"</ETag></CompleteMultipartUploadResult>";

  private Vertx vertx;
  private HttpServer http;
  private final AtomicReference<Function<Captured, Future<Reply>>> responder = new AtomicReference<>();
  private final List<Captured> requests = Collections.synchronizedList(new ArrayList<>());
  /** Completed when a request whose description starts with the key arrives. */
  private final Map<String, Promise<Captured>> arrivals = new ConcurrentHashMap<>();

  @BeforeAll
  void startStub(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    vertx.createHttpServer().requestHandler(this::handle).listen(0, "localhost")
      .onComplete(testContext.succeeding(server -> {
        http = server;
        testContext.completeNow();
      }));
  }

  @AfterAll
  void stopStub(VertxTestContext testContext) {
    http.close().onComplete(testContext.succeedingThenComplete());
  }

  @BeforeEach
  void reset() {
    requests.clear();
    arrivals.clear();
    responder.set(StreamingUploadStubTest::s3);
  }

  private void handle(HttpServerRequest request) {
    request.body().onSuccess(body -> {
      Captured captured = new Captured(request.method(), request.uri(),
        MultiMap.caseInsensitiveMultiMap().addAll(request.headers()), body);
      requests.add(captured);
      arrivals.forEach((prefix, promise) -> {
        if (captured.describe().startsWith(prefix)) {
          promise.tryComplete(captured);
        }
      });
      responder.get().apply(captured).onSuccess(reply -> {
        reply.headers.forEach(request.response()::putHeader);
        request.response().setStatusCode(reply.status).end(reply.body);
      });
    });
  }

  /** A well-behaved S3: answers every request at once. */
  private static Future<Reply> s3(Captured c) {
    if (c.method == HttpMethod.POST && c.uri.endsWith("?uploads=")) {
      return Future.succeededFuture(new Reply(200, CREATED));
    }
    if (c.method == HttpMethod.PUT && c.uri.contains("partNumber=")) {
      return Future.succeededFuture(new Reply(200, "").header("ETag", "\"etag-" + c.query("partNumber") + "\""));
    }
    if (c.method == HttpMethod.POST && c.uri.contains("uploadId=")) {
      return Future.succeededFuture(new Reply(200, COMPLETED));
    }
    if (c.method == HttpMethod.DELETE) {
      return Future.succeededFuture(new Reply(204, ""));
    }
    if (c.method == HttpMethod.PUT) {
      return Future.succeededFuture(new Reply(200, "").header("ETag", "\"etag-single\""));
    }
    return Future.succeededFuture(new Reply(500, "unexpected request"));
  }

  // --- single PutObject or multipart ---------------------------------------------------------------

  @Test
  void emptyStreamIsOneEmptyPutObject(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.end();
    upload.onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
      assertEquals("\"etag-single\"", etag);
      assertEquals(List.of("PUT /bucket/k "), descriptions());
      assertEquals("0", requests.get(0).headers.get("Content-Length"));
      testContext.completeNow();
    })));
  }

  @Test
  void streamWithinOnePartIsOnePutObjectWithItsHashAndOptions(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, new S3PutObjectOptions().setContentType("text/plain"), 4);
    source.emit("ab").compose(v -> source.emit("c")).compose(v -> source.end());
    upload.onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
      assertEquals(List.of("PUT /bucket/k abc"), descriptions());
      assertEquals("text/plain", requests.get(0).headers.get("Content-Type"));
      // Over plain HTTP the body is signed with its SHA-256, computed as the chunks arrived
      assertEquals(HashUtil.sha256Hex("abc".getBytes()), requests.get(0).headers.get("x-amz-content-sha256"));
      testContext.completeNow();
    })));
  }

  /** A full part is only shipped when more bytes follow: exactly one part's worth is still a single PutObject. */
  @Test
  void exactlyOnePartIsOnePutObject(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.emit("abcd").compose(v -> source.end());
    upload.onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
      assertEquals(List.of("PUT /bucket/k abcd"), descriptions());
      testContext.completeNow();
    })));
  }

  @Test
  void onePartAndOneByteIsAMultipartUpload(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, new S3PutObjectOptions().setContentType("video/mp4"), 4);
    source.emit("abcd").compose(v -> source.emit("e")).compose(v -> source.end());
    upload.onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
      assertEquals("\"etag-final\"", etag);
      assertEquals(List.of("POST /bucket/k?uploads= ", "PUT /bucket/k?partNumber=1&uploadId=up-1 abcd",
        "PUT /bucket/k?partNumber=2&uploadId=up-1 e", "POST /bucket/k?uploadId=up-1 "), descriptionsWithoutBodies(3));
      assertEquals("video/mp4", requests.get(0).headers.get("Content-Type"));
      assertEquals(HashUtil.sha256Hex("abcd".getBytes()), requests.get(1).headers.get("x-amz-content-sha256"));
      String complete = requests.get(3).body.toString();
      assertTrue(complete.contains("<Part><PartNumber>1</PartNumber><ETag>\"etag-1\"</ETag></Part>"
        + "<Part><PartNumber>2</PartNumber><ETag>\"etag-2\"</ETag></Part>"), complete);
      testContext.completeNow();
    })));
  }

  /** An exact multiple of the part size, even in one chunk, gives full parts and no empty last part. */
  @Test
  void exactMultipleOfThePartSizeHasNoEmptyPart(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.emit("abcdefgh").compose(v -> source.end());
    upload.onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
      assertEquals(List.of("POST /bucket/k?uploads= ", "PUT /bucket/k?partNumber=1&uploadId=up-1 abcd",
        "PUT /bucket/k?partNumber=2&uploadId=up-1 efgh", "POST /bucket/k?uploadId=up-1 "), descriptionsWithoutBodies(3));
      testContext.completeNow();
    })));
  }

  // --- backpressure --------------------------------------------------------------------------------

  /**
   * One part uploads while the next one fills; once that one is full and another byte arrives, the
   * source is paused until the upload finishes, then resumed.
   */
  @Test
  void sourceIsPausedWhileAFullPartWaitsForTheUploadInFlight(VertxTestContext testContext) {
    Promise<Reply> part1Reply = Promise.promise();
    responder.set(c -> c.describe().startsWith("PUT /bucket/k?partNumber=1&") ? part1Reply.future() : s3(c));
    Future<Captured> part1Sent = arrival("PUT /bucket/k?partNumber=1&");
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.whenResumed()
      .compose(v -> source.emit("abcd"))
      .compose(v -> source.emit("efgh"))
      .compose(v -> part1Sent)
      .compose(v -> {
        testContext.verify(() -> assertFalse(source.isPaused(), "part 1 in flight, part 2 filling: still reading"));
        return source.emit("i");
      })
      .compose(v -> source.whenPaused())
      .compose(v -> {
        testContext.verify(() -> assertEquals(1, partRequests().size(), "part 2 waits for part 1"));
        part1Reply.complete(new Reply(200, "").header("ETag", "\"etag-1\""));
        return source.whenResumed();
      })
      .compose(v -> source.end())
      .compose(v -> upload)
      .onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
        assertEquals(List.of("abcd", "efgh", "i"), partRequests().stream().map(c -> c.body.toString()).collect(Collectors.toList()));
        assertEquals("\"etag-final\"", etag);
        testContext.completeNow();
      })));
  }

  // --- failures ------------------------------------------------------------------------------------

  @Test
  void sourceFailureBeforeTheFirstPartSendsNothing(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    RuntimeException boom = new RuntimeException("source failed");
    source.emit("ab").compose(v -> source.fail(boom));
    upload.onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertSame(boom, e);
      assertEquals(List.of(), descriptions());
      assertTrue(source.isPaused());
      testContext.completeNow();
    })));
  }

  @Test
  void sourceFailureDuringAMultipartUploadAbortsIt(VertxTestContext testContext) {
    Future<Captured> part1Sent = arrival("PUT /bucket/k?partNumber=1&");
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    RuntimeException boom = new RuntimeException("source failed");
    source.emit("abcd").compose(v -> source.emit("ef")).compose(v -> part1Sent).compose(v -> source.fail(boom));
    upload.onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertSame(boom, e);
      List<String> sent = descriptionsWithoutBodies(Integer.MAX_VALUE);
      assertEquals("DELETE /bucket/k?uploadId=up-1 ", sent.get(sent.size() - 1));
      assertFalse(sent.stream().anyMatch(d -> d.startsWith("POST /bucket/k?uploadId=")), "never completed: " + sent);
      testContext.completeNow();
    })));
  }

  @Test
  void partFailureAbortsAndAFailedAbortIsAttached(VertxTestContext testContext) {
    responder.set(c -> c.describe().startsWith("PUT /bucket/k?partNumber=2&")
      ? Future.succeededFuture(new Reply(500, "<Error><Code>InternalError</Code><Message>part</Message></Error>"))
      : c.method == HttpMethod.DELETE
        ? Future.succeededFuture(new Reply(500, "<Error><Code>InternalError</Code><Message>abort</Message></Error>"))
        : s3(c));
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.emit("abcd").compose(v -> source.emit("efgh")).compose(v -> source.emit("i"));
    upload.onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException part = assertInstanceOf(S3ServiceException.class, e);
      assertEquals("InternalError: part (HTTP 500 Internal Server Error)", part.getMessage());
      assertEquals(1, part.getSuppressed().length);
      assertTrue(part.getSuppressed()[0].getMessage().startsWith("InternalError: abort"), part.getSuppressed()[0].getMessage());
      assertTrue(descriptions().contains("DELETE /bucket/k?uploadId=up-1 "), descriptions().toString());
      testContext.completeNow();
    })));
  }

  /** An error inside the 200 response of CompleteMultipartUpload also aborts the upload. */
  @Test
  void completionFailureAborts(VertxTestContext testContext) {
    responder.set(c -> c.method == HttpMethod.POST && c.uri.contains("uploadId=")
      ? Future.succeededFuture(new Reply(200, "<Error><Code>InternalError</Code><Message>late</Message></Error>"))
      : s3(c));
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4);
    source.emit("abcde").compose(v -> source.end());
    upload.onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertEquals(200, assertInstanceOf(S3ServiceException.class, e).getStatusCode());
      List<String> sent = descriptionsWithoutBodies(Integer.MAX_VALUE);
      assertEquals("DELETE /bucket/k?uploadId=up-1 ", sent.get(sent.size() - 1));
      testContext.completeNow();
    })));
  }

  @Test
  void streamOverThePartLimitFailsAndAborts(VertxTestContext testContext) {
    ScriptedReadStream source = source();
    Future<String> upload = upload(client(), source, null, 4, 2);
    source.emit("abcdefghijklm");
    upload.onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      assertEquals("A streamed upload is limited to 2 parts of 4 bytes (8 bytes)", e.getMessage());
      assertEquals(2, partRequests().size());
      List<String> sent = descriptionsWithoutBodies(Integer.MAX_VALUE);
      assertEquals("DELETE /bucket/k?uploadId=up-1 ", sent.get(sent.size() - 1));
      assertTrue(source.isPaused());
      testContext.completeNow();
    })));
  }

  /** Closing the client aborts a streamed upload in progress before the transport closes. */
  @Test
  void closingTheClientAbortsAnUploadInProgress(VertxTestContext testContext) {
    Promise<Reply> part1Reply = Promise.promise();
    responder.set(c -> c.describe().startsWith("PUT /bucket/k?partNumber=1&") ? part1Reply.future() : s3(c));
    Future<Captured> part1Sent = arrival("PUT /bucket/k?partNumber=1&");
    Future<Captured> abortSent = arrival("DELETE /bucket/k?uploadId=up-1");
    S3Client client = client();
    ScriptedReadStream source = source();
    Future<String> upload = upload(client, source, null, 4);
    source.emit("abcd").compose(v -> source.emit("e")).compose(v -> part1Sent).compose(v -> {
      Future<Void> closed = client.close();
      // The part in flight settles first, then the upload is aborted, then the transport closes
      part1Reply.complete(new Reply(200, "").header("ETag", "\"etag-1\""));
      return closed;
    }).onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      assertTrue(abortSent.succeeded(), "aborted before the close completed");
      assertTrue(upload.failed());
      assertEquals("The S3 client was closed during the upload", upload.cause().getMessage());
      testContext.completeNow();
    })));
  }

  @Test
  void invalidArgumentsLeaveTheStreamPausedAndUnread(VertxTestContext testContext) {
    S3Client client = client();
    ScriptedReadStream source = source();
    client.putObject("Bad_Bucket", "k", source).onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      assertTrue(source.isPaused());
      assertFalse(source.handlerSet(), "never read");
      assertEquals(List.of(), descriptions());
      testContext.completeNow();
    })));
  }

  // --- helpers -------------------------------------------------------------------------------------

  private S3ClientImpl client() {
    return (S3ClientImpl) S3Client.create(vertx, new S3ClientOptions()
      .setEndpoint("http://localhost:" + http.actualPort())
      .setRegion("test-west-1")
      .setCredentials(new S3Credentials("AKIDEXAMPLE", "secret")));
  }

  /** A source owned by its own context, unlike the caller's. */
  private ScriptedReadStream source() {
    return new ScriptedReadStream(vertx.getOrCreateContext());
  }

  private Future<String> upload(S3Client client, ScriptedReadStream source, S3PutObjectOptions options, int partSize) {
    return upload(client, source, options, partSize, MultipartRequests.MAX_PARTS);
  }

  private Future<String> upload(S3Client client, ScriptedReadStream source, S3PutObjectOptions options, int partSize,
                                int maxParts) {
    return ((S3ClientImpl) client).putObject("bucket", "k", source, options, partSize, maxParts);
  }

  private Future<Captured> arrival(String prefix) {
    Promise<Captured> promise = Promise.promise();
    arrivals.put(prefix, promise);
    return promise.future();
  }

  private List<String> descriptions() {
    synchronized (requests) {
      return requests.stream().map(Captured::describe).collect(Collectors.toList());
    }
  }

  /** The descriptions, with bodies only for the first {@code withBodies} requests. */
  private List<String> descriptionsWithoutBodies(int withBodies) {
    synchronized (requests) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < requests.size(); i++) {
        Captured c = requests.get(i);
        out.add(i < withBodies && c.method == HttpMethod.PUT ? c.describe() : c.method + " " + c.uri + " ");
      }
      return out;
    }
  }

  private List<Captured> partRequests() {
    synchronized (requests) {
      return requests.stream().filter(c -> c.uri.contains("partNumber=")).collect(Collectors.toList());
    }
  }

  private static final class Captured {
    final HttpMethod method;
    final String uri;
    final MultiMap headers;
    final Buffer body;

    Captured(HttpMethod method, String uri, MultiMap headers, Buffer body) {
      this.method = method;
      this.uri = uri;
      this.headers = headers;
      this.body = body;
    }

    String describe() {
      return method + " " + uri + " " + body;
    }

    String query(String name) {
      return uri.replaceAll(".*[?&]" + name + "=([^&]*).*", "$1");
    }
  }

  private static final class Reply {
    final int status;
    final String body;
    final Map<String, String> headers = new java.util.LinkedHashMap<>();

    Reply(int status, String body) {
      this.status = status;
      this.body = body;
    }

    Reply header(String name, String value) {
      headers.put(name, value);
      return this;
    }
  }
}
