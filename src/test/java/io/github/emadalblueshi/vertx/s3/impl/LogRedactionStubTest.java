package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Log redaction against a local HTTP(S) stub whose responses echo the request's credentials: the
 * warning for {@code logActivity}, what Netty's wire log exposes (the control for every "nothing
 * logged" check), and that no exception carries a secret, whatever the server sends back. The
 * {@code S3ClientLogRedaction*} suites cover the main operations against real servers.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogRedactionStubTest {

  private static final String ACCESS_KEY = "AKIDLOGREDACTIONTEST";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYLOGREDACT01";
  private static final String TOKEN = "IQoJb3JpZ2luX2VjEJr//////////wEaCXVzLWVhc3QtMSJHMEUCIQDtestOnlyToken+abc=";
  private static final String REQUEST_ID = "4442587FB7D0A2F9";
  private static final String HOST_ID = "esJ1KWWtYEs1wpIUhRsa3ZA3o24pfMhK2aoOqwfc0bR5Lmf9zi3Wu2NEXg4ZPEPHHhBMP0Esp8c=";
  private static final String SIGNATURE_DOES_NOT_MATCH = "The request signature we calculated does not match the "
    + "signature you provided. Check your key and signing method.";
  private static final String LIST_BUCKETS = "<ListAllMyBucketsResult><Owner><ID>owner-id</ID></Owner><Buckets>"
    + "</Buckets></ListAllMyBucketsResult>";
  private static final Pattern AUTHORIZATION = Pattern.compile(
    "AWS4-HMAC-SHA256 Credential=([^/]+)/([^,]+), SignedHeaders=([^,]+), Signature=([0-9a-f]+)");

  private Vertx vertx;
  private HttpServer http;
  private HttpServer https;
  private final AtomicReference<Function<Request, Reply>> responder = new AtomicReference<>();
  private final AtomicReference<Request> seen = new AtomicReference<>();
  private final AtomicReference<Reply> sent = new AtomicReference<>();

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
    responder.set(request -> new Reply(200, LIST_BUCKETS));
    seen.set(null);
    sent.set(null);
  }

  private void handle(HttpServerRequest request) {
    request.body().onSuccess(body -> {
      Request captured = new Request(request.method().name(), request.path(),
        request.query() == null ? "" : request.query(), MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
      seen.set(captured);
      Reply reply = responder.get().apply(captured);
      sent.set(reply);
      HttpServerResponse response = request.response().setStatusCode(reply.status);
      if (reply.statusMessage != null) {
        response.setStatusMessage(reply.statusMessage);
      }
      reply.headers.forEach(response::putHeader);
      response.end(reply.body);
    });
  }

  // --- the warning, and what Netty's wire log exposes ----------------------------------------------

  @Test
  void aClientCreatedWithLogActivityWarnsOnce(VertxTestContext testContext) {
    S3Client quiet;
    S3Client noisy;
    String logged;
    try (VertxLogCapture library = VertxLogCapture.library()) {
      quiet = S3Client.create(vertx, options(false));
      testContext.verify(() -> assertEquals("", library.logged(), "logActivity off: nothing"));
      noisy = S3Client.create(vertx, options(true));
      logged = library.logged();
    }
    testContext.verify(() -> {
      assertEquals(1, logged.split(Pattern.quote(S3ClientImpl.WIRE_LOG_WARNING), -1).length - 1, logged);
      assertTrue(logged.contains("io.github.emadalblueshi.vertx.s3.S3Client"), logged);
      assertTrue(logged.contains("WARNING: " + S3ClientImpl.WIRE_LOG_WARNING), logged);
      Leaks.assertNoLeak("the warning", logged, List.of(SECRET_KEY, TOKEN, ACCESS_KEY), List.of());
    });
    Future.all(quiet.close(), noisy.close()).onComplete(testContext.succeedingThenComplete());
  }

  /**
   * The control for every "nothing logged" check: with {@code logActivity} on, the capture sees
   * Netty's hex dump of the traffic, credentials included, which no library can redact. With it
   * off, nothing of the traffic is logged at any level.
   */
  @ParameterizedTest(name = "logActivity={0}")
  @ValueSource(booleans = {true, false})
  void nettyLogsTheCredentialsOnlyWithLogActivity(boolean logActivity, VertxTestContext testContext) {
    VertxLogCapture library = VertxLogCapture.library();
    VertxLogCapture everything = VertxLogCapture.everything();
    S3Client client = S3Client.create(vertx, options(logActivity));
    client.listBuckets()
      .eventually(client::close)
      .onComplete(outcome -> {
        everything.close();
        library.close();
        testContext.verify(() -> {
          assertTrue(outcome.succeeded(), () -> String.valueOf(outcome.cause()));
          String authorization = seen.get().headers.get("Authorization");
          String wire = everything.hexDumps();
          if (logActivity) {
            assertTrue(wire.contains(authorization), "Netty's hex dump shows the Authorization value");
            assertTrue(wire.contains(TOKEN), "and the session token");
            assertTrue(library.logged().contains(S3ClientImpl.WIRE_LOG_WARNING), library.logged());
          } else {
            assertEquals("", wire, "no hex dump");
            assertEquals("", library.logged());
            Leaks.assertNoLeak("the log", everything.logged(), secrets(), Leaks.LOG_MARKERS);
          }
          testContext.completeNow();
        });
      });
  }

  // --- what servers echo is kept out of exceptions --------------------------------------------------

  /** AWS's SignatureDoesNotMatch document echoes the access key, the signature and the canonical request. */
  @Test
  void anAwsSignatureErrorCarriesNoCredentials(VertxTestContext testContext) {
    responder.set(request -> new Reply(403, signatureDoesNotMatch(request)).header("Content-Type", "application/xml"));
    expectFailure(testContext, client -> client.getObject("my-bucket", "photos/a.jpg"), e -> {
      assertEchoed(TOKEN, ACCESS_KEY, signature());
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("SignatureDoesNotMatch", service.getErrorCode());
      assertEquals("SignatureDoesNotMatch: " + SIGNATURE_DOES_NOT_MATCH + " (HTTP 403 Forbidden)", service.getMessage());
      assertEquals(REQUEST_ID, service.getRequestId());
      assertEquals(HOST_ID, service.getHostId());
      assertEquals(Map.of(), service.getDetails(), "AWS's signature-debugging fields are left out");
    });
  }

  /** AWS echoes the session token as {@code Token-0} with {@code ExpiredToken} and {@code InvalidToken}. */
  @Test
  void anAwsTokenErrorCarriesNoToken(VertxTestContext testContext) {
    responder.set(request -> new Reply(400, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>ExpiredToken</Code>"
      + "<Message>The provided token has expired.</Message><Token-0>" + request.headers.get("X-Amz-Security-Token")
      + "</Token-0><RequestId>" + REQUEST_ID + "</RequestId><HostId>" + HOST_ID + "</HostId></Error>"));
    expectFailure(testContext, client -> client.listBuckets(), e -> {
      assertEchoed(TOKEN);
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals("ExpiredToken: The provided token has expired. (HTTP 400 Bad Request)", service.getMessage());
      assertEquals(Map.of("Token-0", Redaction.REDACTED), service.getDetails());
    });
  }

  /** Whatever field a server echoes a secret in, it's replaced; the rest of the field stays. */
  @Test
  void secretsEchoedInAnyFieldAreRedacted(VertxTestContext testContext) {
    responder.set(request -> {
      String authorization = request.headers.get("Authorization");
      String signature = Redaction.signature(authorization);
      return new Reply(400, "<Error><Code>InvalidArgument</Code><Message>Rejected Authorization: " + authorization
        + " with X-Amz-Security-Token: " + TOKEN + "</Message><Resource>/my-bucket/a.txt?sig=" + signature + "</Resource>"
        + "<RequestId>" + TOKEN + "</RequestId><HostId>" + signature + "</HostId><ArgumentName>Authorization</ArgumentName>"
        + "<ArgumentValue>" + authorization + "</ArgumentValue><Echo>" + SECRET_KEY + "</Echo></Error>")
        .statusMessage("Bad " + TOKEN);
    });
    expectFailure(testContext, client -> client.getObject("my-bucket", "a.txt"), e -> {
      assertEchoed(TOKEN, SECRET_KEY, signature());
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals("InvalidArgument: Rejected Authorization: [REDACTED] with X-Amz-Security-Token: [REDACTED] "
        + "(HTTP 400 Bad [REDACTED])", service.getMessage());
      assertEquals("/my-bucket/a.txt?sig=[REDACTED]", service.getResource());
      assertEquals(Redaction.REDACTED, service.getRequestId());
      assertEquals(Redaction.REDACTED, service.getHostId());
      Map<String, String> details = new LinkedHashMap<>();
      details.put("ArgumentName", "Authorization");
      details.put("ArgumentValue", Redaction.REDACTED);
      details.put("Echo", Redaction.REDACTED);
      assertEquals(details, service.getDetails());
    });
  }

  /**
   * A proxy's error page quoting the request's headers: the excerpt is cut at
   * {@link ErrorMapper#MAX_EXCERPT} characters, inside the Authorization value here, so it is
   * redacted before it is cut.
   */
  @Test
  void aProxyPageQuotingTheHeadersIsRedactedBeforeItIsCut(VertxTestContext testContext) {
    String before = "<html><head><title>400 Bad Request</title></head><body><h1>Bad Request</h1>"
      + "<p>The proxy rejected these request headers:</p><pre>\nAuthorization: ";
    responder.set(request -> new Reply(400, before + request.headers.get("Authorization") + "\nX-Amz-Security-Token: "
      + TOKEN + "\n</pre></body></html>").header("Content-Type", "text/html"));
    expectFailure(testContext, client -> client.listBuckets(), e -> {
      String authorization = seen.get().headers.get("Authorization");
      int cleanedBefore = before.length(); // the newline becomes one space
      assertTrue(cleanedBefore < ErrorMapper.MAX_EXCERPT && cleanedBefore + authorization.length() > ErrorMapper.MAX_EXCERPT,
        "the cut falls inside the Authorization value");
      assertTrue(e.getMessage().startsWith("HTTP 400 Bad Request: <html><head><title>400 Bad Request</title></head><body>"
        + "<h1>Bad Request</h1><p>The proxy rejected these request headers:</p><pre> Authorization: [REDACTED] "),
        e.getMessage());
    });
  }

  /** A HEAD error has no body: the request ids and the bucket region come from headers, redacted too. */
  @Test
  void headErrorHeadersAreRedacted(VertxTestContext testContext) {
    responder.set(request -> new Reply(403, "").header("x-amz-request-id", TOKEN)
      .header("x-amz-id-2", Redaction.signature(request.headers.get("Authorization"))).header("x-amz-bucket-region", SECRET_KEY));
    expectFailure(testContext, client -> client.headObject("my-bucket", "a.txt"), e -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals("HTTP 403 Forbidden (no error document)", service.getMessage());
      assertEquals(Redaction.REDACTED, service.getRequestId());
      assertEquals(Redaction.REDACTED, service.getHostId());
      assertEquals(Map.of(ErrorMapper.BUCKET_REGION, Redaction.REDACTED), service.getDetails());
    });
  }

  /** CompleteMultipartUpload can report a failure inside a 200 response: redacted the same way. */
  @Test
  void aCompletionErrorInsideA200IsRedacted(VertxTestContext testContext) {
    responder.set(request -> new Reply(200, "  \n  <Error><Code>InternalError</Code><Message>We encountered an internal "
      + "error with token " + TOKEN + ". Please try again.</Message><StringToSign>AWS4-HMAC-SHA256\n"
      + request.headers.get("X-Amz-Date") + "</StringToSign><RequestId>" + REQUEST_ID + "</RequestId></Error>"));
    expectFailure(testContext, client -> client.completeMultipartUpload("my-bucket", "video.mp4", "upload-1",
      List.of(new S3CompletedPart(1, "\"etag-1\""))), e -> {
      assertEchoed(TOKEN);
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(200, service.getStatusCode());
      assertEquals("InternalError: We encountered an internal error with token [REDACTED]. Please try again. "
        + "(HTTP 200 OK)", service.getMessage());
      assertEquals(Map.of(), service.getDetails());
    });
  }

  @Test
  void aRefusedConnectionCarriesNoSecrets(VertxTestContext testContext) throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    S3ClientOptions options = options(false).setEndpoint("http://localhost:" + port);
    expectFailure(testContext, options, client -> client.listBuckets(), e -> assertInstanceOf(S3ClientException.class, e));
  }

  @Test
  void anUntrustedCertificateCarriesNoSecrets(VertxTestContext testContext) {
    S3ClientOptions options = options(false).setEndpoint("https://localhost:" + https.actualPort());
    expectFailure(testContext, options, client -> client.listBuckets(), e -> assertInstanceOf(S3ClientException.class, e));
  }

  // --- helpers ---------------------------------------------------------------------------------------

  private void expectFailure(VertxTestContext testContext, Function<S3Client, Future<?>> call, Consumer<Throwable> check) {
    expectFailure(testContext, options(false), call, check);
  }

  /**
   * Runs {@code call} with a client created from {@code options}, expects it to fail, checks the
   * failure, and that neither the exception (as a log would show it) nor anything logged meanwhile,
   * at any level, contains a secret.
   */
  private void expectFailure(VertxTestContext testContext, S3ClientOptions options, Function<S3Client, Future<?>> call,
                             Consumer<Throwable> check) {
    VertxLogCapture everything = VertxLogCapture.everything();
    S3Client client = S3Client.create(vertx, options);
    call.apply(client)
      .eventually(client::close)
      .onComplete(outcome -> {
        everything.close();
        testContext.verify(() -> {
          assertTrue(outcome.failed(), "expected a failure");
          check.accept(outcome.cause());
          Leaks.assertNoLeak("the exception", Leaks.asLogged(outcome.cause()), secrets(), Leaks.PRESIGNED_QUERY_MARKERS);
          Leaks.assertNoLeak("the log", everything.logged(), secrets(), Leaks.LOG_MARKERS);
          testContext.completeNow();
        });
      });
  }

  /** Checks that the stub's response did echo {@code values}, so the test exercises redaction. */
  private void assertEchoed(String... values) {
    String reply = sent.get().statusMessage + " " + sent.get().headers + " " + sent.get().body;
    for (String value : values) {
      assertTrue(reply.contains(value), "the stub was expected to echo a secret");
    }
  }

  /** The secret key, the session token, the access key and the last request's signature. */
  private List<String> secrets() {
    List<String> secrets = new ArrayList<>(List.of(SECRET_KEY, TOKEN, ACCESS_KEY));
    if (seen.get() != null) {
      secrets.add(signature());
    }
    return secrets;
  }

  private String signature() {
    return Redaction.signature(seen.get().headers.get("Authorization"));
  }

  private S3ClientOptions options(boolean logActivity) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("http://localhost:" + http.actualPort())
      .setRegion("test-west-1")
      .setCredentials(new S3Credentials(ACCESS_KEY, SECRET_KEY).setSessionToken(TOKEN));
    options.getHttpClientOptions().setLogActivity(logActivity);
    return options;
  }

  /**
   * AWS's SignatureDoesNotMatch document for {@code request}: the elements AWS sends, in its order,
   * filled in from the request as AWS fills them in. The canonical request lists every signed
   * header, the session token included. Layout as in responses quoted in AWS SDK issues (e.g.
   * aws/aws-sdk-net#1205).
   */
  private static String signatureDoesNotMatch(Request request) {
    Matcher authorization = AUTHORIZATION.matcher(request.headers.get("Authorization"));
    if (!authorization.matches()) {
      return "<Error><Code>UnexpectedAuthorization</Code></Error>";
    }
    List<String> signedHeaders = List.of(authorization.group(3).split(";"));
    StringBuilder canonical = new StringBuilder()
      .append(request.method).append('\n').append(request.path).append('\n').append(request.query).append('\n');
    for (String header : signedHeaders) {
      canonical.append(header).append(':').append(request.headers.get(header).trim()).append('\n');
    }
    canonical.append('\n').append(String.join(";", signedHeaders)).append('\n')
      .append(request.headers.get("x-amz-content-sha256"));
    String canonicalRequest = canonical.toString();
    String stringToSign = "AWS4-HMAC-SHA256\n" + request.headers.get("x-amz-date") + "\n" + authorization.group(2) + "\n"
      + HashUtil.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>SignatureDoesNotMatch</Code><Message>"
      + SIGNATURE_DOES_NOT_MATCH + "</Message><AWSAccessKeyId>" + authorization.group(1) + "</AWSAccessKeyId>"
      + "<StringToSign>" + stringToSign + "</StringToSign><SignatureProvided>" + authorization.group(4)
      + "</SignatureProvided><StringToSignBytes>" + bytes(stringToSign) + "</StringToSignBytes><CanonicalRequest>"
      + escape(canonicalRequest) + "</CanonicalRequest><CanonicalRequestBytes>" + bytes(canonicalRequest)
      + "</CanonicalRequestBytes><RequestId>" + REQUEST_ID + "</RequestId><HostId>" + HOST_ID + "</HostId></Error>";
  }

  /** The bytes of {@code text} in hex, separated by spaces, as AWS writes its {@code *Bytes} fields. */
  private static String bytes(String text) {
    StringBuilder hex = new StringBuilder();
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      hex.append(hex.length() == 0 ? "" : " ").append(String.format("%02x", b & 0xff));
    }
    return hex.toString();
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static final class Request {
    final String method;
    final String path;
    final String query;
    final MultiMap headers;

    Request(String method, String path, String query, MultiMap headers) {
      this.method = method;
      this.path = path;
      this.query = query;
      this.headers = headers;
    }
  }

  private static final class Reply {
    final int status;
    final String body;
    final Map<String, String> headers = new LinkedHashMap<>();
    String statusMessage;

    Reply(int status, String body) {
      this.status = status;
      this.body = body;
    }

    Reply header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    Reply statusMessage(String message) {
      statusMessage = message;
      return this;
    }
  }
}
