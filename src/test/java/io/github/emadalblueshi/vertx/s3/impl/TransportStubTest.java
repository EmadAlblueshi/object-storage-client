package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * The transport against a local Vert.x HTTP(S) stub: what is sent on the wire (path, query,
 * Host, signing headers, Content-Length, payload hash) and how responses and failures are mapped.
 * Signature validity itself is verified against Silo.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransportStubTest {

  private static final String LIST_BUCKETS = "<ListAllMyBucketsResult><Owner><ID>owner-id</ID></Owner><Buckets>"
    + "<Bucket><Name>b1</Name><CreationDate>2026-11-20T10:15:30.000Z</CreationDate></Bucket></Buckets>"
    + "</ListAllMyBucketsResult>";

  private Vertx vertx;
  private HttpServer http;
  private HttpServer https;
  private final AtomicReference<Function<Captured, Stub>> responder = new AtomicReference<>();
  private final AtomicReference<Captured> last = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();

  @BeforeAll
  void startStubs(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    HttpServerOptions tls = new HttpServerOptions().setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
      .setCertPath("tls/test-only-silo-selfsigned.crt").setKeyPath("tls/test-only-silo-selfsigned.key"));
    Future.all(
        vertx.createHttpServer().requestHandler(this::handle).listen(0, "localhost").onSuccess(s -> http = s),
        vertx.createHttpServer(tls).requestHandler(this::handle).listen(0, "localhost").onSuccess(s -> https = s))
      .onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void stopStubs(VertxTestContext testContext) {
    Future.all(http.close(), https.close()).onComplete(testContext.succeedingThenComplete());
  }

  @BeforeEach
  void reset() {
    responder.set(c -> new Stub(200, LIST_BUCKETS));
    last.set(null);
    requests.set(0);
  }

  private void handle(io.vertx.core.http.HttpServerRequest request) {
    request.body().onSuccess(body -> {
      Captured captured = new Captured(request.method(), request.uri(),
        MultiMap.caseInsensitiveMultiMap().addAll(request.headers()), body);
      last.set(captured);
      requests.incrementAndGet();
      Stub stub = responder.get().apply(captured);
      io.vertx.core.http.HttpServerResponse response = request.response().setStatusCode(stub.status);
      stub.headers.forEach(response::putHeader);
      response.end(stub.body);
    });
  }

  // --- what goes on the wire -----------------------------------------------------------------------

  @Test
  void listBucketsSendsASignedGetAndParsesTheResult(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    client.listBuckets().eventually(client::close).onComplete(testContext.succeeding(result -> testContext.verify(() -> {
      assertEquals("b1", result.getBuckets().get(0).getName());
      assertEquals("owner-id", result.getOwner().getId());
      Captured c = last.get();
      assertEquals(HttpMethod.GET, c.method);
      assertEquals("/", c.uri);
      assertEquals("localhost:" + http.actualPort(), c.headers.get("Host"));
      assertTrue(c.headers.get("Authorization").startsWith(
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), c.headers.get("Authorization"));
      assertTrue(c.headers.get("Authorization").contains("/test-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "));
      assertEquals(S3SignatureV4.EMPTY_PAYLOAD_SHA256, c.headers.get("x-amz-content-sha256"));
      assertNotNull(c.headers.get("x-amz-date"));
      assertNull(c.headers.get("Content-Length"), "no Content-Length for a bodiless GET");
      testContext.completeNow();
    })));
  }

  @Test
  void smallBodyIsSignedInlineWithEncodedPathAndQuery(VertxTestContext testContext) {
    Transport transport = transport(options(http.actualPort(), false));
    S3Request request = new S3Request(HttpMethod.PUT, "bucket", "dir/a b+c.txt",
      List.of(Map.entry("tagging", ""), Map.entry("versionId", "v/1")), List.of(Map.entry("Content-Type", "text/plain")),
      Buffer.buffer("hello"));
    responder.set(c -> new Stub(200, ""));
    transport.execute(ContextSupport.capture(vertx), request).eventually(transport::close)
      .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        Captured c = last.get();
        assertEquals("/bucket/dir/a%20b%2Bc.txt?tagging=&versionId=v%2F1", c.uri);
        assertEquals("5", c.headers.get("Content-Length"));
        assertEquals("hello", c.body.toString());
        assertEquals(HashUtil.sha256Hex("hello"), c.headers.get("x-amz-content-sha256"));
        assertEquals("text/plain", c.headers.get("Content-Type"));
        assertTrue(c.headers.get("Authorization").contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date,"));
        testContext.completeNow();
      })));
  }

  @Test
  void largeBodyOverHttpIsHashedOffTheCallerThreadAndStillCorrect(VertxTestContext testContext) {
    byte[] data = new byte[PayloadMode.MAX_INLINE_HASH_SIZE + 1];
    new Random(42).nextBytes(data);
    Transport transport = transport(options(http.actualPort(), false));
    responder.set(c -> new Stub(200, ""));
    transport.execute(ContextSupport.capture(vertx), new S3Request(HttpMethod.PUT, "bucket", "big.bin", List.of(), List.of(),
        Buffer.buffer(data))).eventually(transport::close)
      .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        assertEquals(HashUtil.sha256Hex(data), last.get().headers.get("x-amz-content-sha256"));
        assertEquals(Integer.toString(data.length), last.get().headers.get("Content-Length"));
        testContext.completeNow();
      })));
  }

  @Test
  void putWithoutBodySendsContentLengthZero(VertxTestContext testContext) {
    Transport transport = transport(options(http.actualPort(), false));
    responder.set(c -> new Stub(200, ""));
    transport.execute(ContextSupport.capture(vertx), new S3Request(HttpMethod.PUT, "bucket", null, List.of(), List.of(), null))
      .eventually(transport::close)
      .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        assertEquals("/bucket", last.get().uri);
        assertEquals("0", last.get().headers.get("Content-Length"));
        assertEquals(S3SignatureV4.EMPTY_PAYLOAD_SHA256, last.get().headers.get("x-amz-content-sha256"));
        testContext.completeNow();
      })));
  }

  @Test
  void bodiesAreUnsignedOverTls(VertxTestContext testContext) {
    Transport transport = transport(options(https.actualPort(), true));
    responder.set(c -> new Stub(200, ""));
    transport.execute(ContextSupport.capture(vertx), new S3Request(HttpMethod.PUT, "bucket", "k", List.of(), List.of(),
        Buffer.buffer("data"))).eventually(transport::close)
      .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        assertEquals(S3SignatureV4.UNSIGNED_PAYLOAD, last.get().headers.get("x-amz-content-sha256"));
        assertEquals("localhost:" + https.actualPort(), last.get().headers.get("Host"));
        testContext.completeNow();
      })));
  }

  @Test
  void bodilessRequestsAreUnsignedOverTls(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(https.actualPort(), true));
    client.listBuckets().eventually(client::close).onComplete(testContext.succeeding(result -> testContext.verify(() -> {
      assertEquals(S3SignatureV4.UNSIGNED_PAYLOAD, last.get().headers.get("x-amz-content-sha256"));
      assertTrue(last.get().headers.get("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date,"));
      testContext.completeNow();
    })));
  }

  @Test
  void sessionTokenIsSentAndSigned(VertxTestContext testContext) {
    S3ClientOptions options = options(http.actualPort(), false).setCredentials(null)
      .setCredentialsProvider(() -> new S3Credentials("AKIDEXAMPLE", "secret").setSessionToken("token-123"));
    S3Client client = S3Client.create(vertx, options);
    client.listBuckets().eventually(client::close).onComplete(testContext.succeeding(result -> testContext.verify(() -> {
      assertEquals("token-123", last.get().headers.get("x-amz-security-token"));
      assertTrue(last.get().headers.get("Authorization").contains(";x-amz-security-token,"));
      testContext.completeNow();
    })));
  }

  @Test
  void defaultPortIsOmittedFromTheAuthority() {
    // Pins the Vert.x behaviour the transport relies on for default ports (the signer omits them too).
    assertEquals("s3.example.com", HostAndPort.authority("s3.example.com", -1).toString());
    assertEquals(S3SignatureV4.hostHeader("s3.example.com", -1, true), HostAndPort.authority("s3.example.com", -1).toString());
  }

  @Test
  void userAgentIsSentButNotSigned(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    client.listBuckets().eventually(client::close).onComplete(testContext.succeeding(result -> testContext.verify(() -> {
      Captured c = last.get();
      assertEquals(UserAgent.VALUE, c.headers.get("User-Agent"));
      assertTrue(c.headers.get("User-Agent").matches("vertx-s3-client/\\d+\\.\\d+\\.\\d+.*"), c.headers.get("User-Agent"));
      assertFalse(c.headers.get("Authorization").contains("user-agent"), c.headers.get("Authorization"));
      testContext.completeNow();
    })));
  }

  @Test
  void createBucketSendsTheLocationConstraint(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    responder.set(c -> new Stub(200, ""));
    client.createBucket("my-bucket").eventually(client::close).onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      Captured c = last.get();
      assertEquals(HttpMethod.PUT, c.method);
      assertEquals("/my-bucket", c.uri);
      String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CreateBucketConfiguration "
        + "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><LocationConstraint>test-west-1</LocationConstraint>"
        + "</CreateBucketConfiguration>";
      assertEquals(body, c.body.toString());
      assertEquals(Integer.toString(body.length()), c.headers.get("Content-Length"));
      assertEquals(HashUtil.sha256Hex(body), c.headers.get("x-amz-content-sha256"));
      testContext.completeNow();
    })));
  }

  @Test
  void createBucketInUsEast1SendsNoBody(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false).setRegion("us-east-1"));
    responder.set(c -> new Stub(200, ""));
    client.createBucket("my-bucket").eventually(client::close).onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      Captured c = last.get();
      assertEquals(0, c.body.length());
      assertEquals("0", c.headers.get("Content-Length"));
      assertEquals(S3SignatureV4.EMPTY_PAYLOAD_SHA256, c.headers.get("x-amz-content-sha256"));
      testContext.completeNow();
    })));
  }

  @Test
  void headAndDeleteBucketUseTheirMethods(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    responder.set(c -> new Stub(c.method == HttpMethod.HEAD ? 200 : 204, ""));
    client.headBucket("my-bucket")
      .compose(v -> {
        testContext.verify(() -> {
          assertEquals(HttpMethod.HEAD, last.get().method);
          assertEquals("/my-bucket", last.get().uri);
        });
        return client.deleteBucket("my-bucket");
      })
      .eventually(client::close)
      .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
        assertEquals(HttpMethod.DELETE, last.get().method);
        assertEquals("/my-bucket", last.get().uri);
        assertEquals(2, requests.get());
        testContext.completeNow();
      })));
  }

  @Test
  void invalidBucketNameFailsWithoutAnyRequest(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    client.createBucket("Invalid_Name").eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      assertTrue(e.getMessage().startsWith("Invalid bucket name \"Invalid_Name\""), e.getMessage());
      assertEquals(0, requests.get());
      testContext.completeNow();
    })));
  }

  // --- responses and failures -------------------------------------------------------------------------

  @Test
  void headBucket404HasStatusButNoErrorCode(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    responder.set(c -> new Stub(404, "").header("x-amz-request-id", "req-404"));
    client.headBucket("missing").eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(404, service.getStatusCode());
      assertNull(service.getErrorCode());
      assertEquals("req-404", service.getRequestId());
      testContext.completeNow();
    })));
  }

  @Test
  void errorDocumentBecomesServiceException(VertxTestContext testContext) {
    responder.set(c -> new Stub(403, "<Error><Code>SignatureDoesNotMatch</Code><Message>Bad signature</Message>"
      + "<RequestId>REQ</RequestId></Error>"));
    expectFailure(testContext, e -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("SignatureDoesNotMatch", service.getErrorCode());
      assertEquals("REQ", service.getRequestId());
    });
  }

  @Test
  void proxyHtmlPageBecomesServiceExceptionWithStatus(VertxTestContext testContext) {
    responder.set(c -> new Stub(502, "<html><body>Bad Gateway</body></html>"));
    expectFailure(testContext, e -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(502, service.getStatusCode());
      assertNull(service.getErrorCode());
      assertTrue(service.getMessage().contains("Bad Gateway"), service.getMessage());
    });
  }

  @Test
  void redirectsAreNotFollowed(VertxTestContext testContext) {
    responder.set(c -> new Stub(307, "<Error><Code>TemporaryRedirect</Code></Error>")
      .header("Location", "http://localhost:" + http.actualPort() + "/elsewhere"));
    expectFailure(testContext, e -> {
      assertEquals(307, assertInstanceOf(S3ServiceException.class, e).getStatusCode());
      assertEquals(1, requests.get(), "the redirect must not be followed");
    });
  }

  @Test
  void malformedSuccessBodyBecomesClientException(VertxTestContext testContext) {
    responder.set(c -> new Stub(200, "<ListAllMyBucketsResult><Buckets>"));
    expectFailure(testContext, e -> {
      assertInstanceOf(S3ClientException.class, e);
      assertInstanceOf(XmlParseException.class, e.getCause());
    });
  }

  @Test
  void connectionRefusedBecomesClientException(VertxTestContext testContext) throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    S3Client client = S3Client.create(vertx, options(closedPort, false));
    client.listBuckets().eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      assertNotNull(e.getCause());
      testContext.completeNow();
    })));
  }

  @Test
  void failingCredentialsProviderBecomesClientException(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false).setCredentials(null)
      .setCredentialsProvider(() -> {
        throw new IllegalStateException("vault unavailable");
      }));
    client.listBuckets().eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      assertEquals("vault unavailable", e.getCause().getMessage());
      assertEquals(0, requests.get(), "nothing is sent without credentials");
      testContext.completeNow();
    })));
  }

  @Test
  void credentialsProviderReturningNullBecomesClientException(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false).setCredentials(null)
      .setCredentialsProvider(() -> null));
    client.listBuckets().eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      assertInstanceOf(S3ClientException.class, e);
      testContext.completeNow();
    })));
  }

  @Test
  void closedClientFailsAndCloseIsIdempotent(VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    client.close()
      .compose(v -> client.close())
      .compose(v -> client.listBuckets())
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
        assertFalse(requests.get() > 0);
        testContext.completeNow();
      })));
  }

  // --- helpers ---------------------------------------------------------------------------------------

  private void expectFailure(VertxTestContext testContext, java.util.function.Consumer<Throwable> check) {
    S3Client client = S3Client.create(vertx, options(http.actualPort(), false));
    client.listBuckets().eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        check.accept(e);
        testContext.completeNow();
      })));
  }

  private Transport transport(S3ClientOptions options) {
    return new Transport(vertx, ClientConfig.from(options));
  }

  private static S3ClientOptions options(int port, boolean tls) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint((tls ? "https" : "http") + "://localhost:" + port)
      .setRegion("test-west-1")
      .setCredentials(new S3Credentials("AKIDEXAMPLE", "secret"));
    if (tls) {
      options.getHttpClientOptions().setTrustOptions(new PemTrustOptions().addCertPath("tls/test-only-silo-selfsigned.crt"));
    }
    return options;
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
  }

  private static final class Stub {
    final int status;
    final String body;
    final Map<String, String> headers = new java.util.LinkedHashMap<>();

    Stub(int status, String body) {
      this.status = status;
      this.body = body;
    }

    Stub header(String name, String value) {
      headers.put(name, value);
      return this;
    }
  }
}
