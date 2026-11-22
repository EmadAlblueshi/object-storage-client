package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignGetOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Presigned URLs end to end, against one {@link S3TestBackend} per subclass (Silo over HTTP on a
 * non-default port, Silo over HTTPS, and the opt-in external backend). The URLs are used the way a
 * browser or mobile app uses them: with a plain Vert.x {@code HttpClient}, no credentials, the URL
 * sent exactly as returned ({@code setAbsoluteURI} keeps its encoding, and the {@code Host} header
 * comes from it). The server verifies every signature; an expired URL is signed in the past through
 * the {@code Clock} test hook, so nothing waits.
 * <p>
 * Safe on a real account: all objects live in one uniquely named bucket, created once for the
 * suite; every key is registered before anything writes it, and cleanup deletes the objects and the
 * bucket even when tests fail.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientPresignTestBase {

  /** An endpoint that is never contacted: it stands for an in-cluster address next to a public endpoint. */
  private static final String INTERNAL_ENDPOINT = "https://s3.internal.invalid";

  private static final Pattern ERROR_CODE = Pattern.compile("<Code>([^<]*)</Code>");

  protected Vertx vertx;
  protected S3TestBackend backend;
  protected S3Client client;
  private HttpClientAgent http;
  private Context context;
  protected final TestResources resources = new TestResources();
  protected final String bucket = resources.bucket(TestResources.uniqueBucketName());

  /** The backend for this suite; may abort the class (skipped) if it isn't available. */
  protected abstract S3TestBackend backend();

  /**
   * The HTTP status for an upload that leaves out a header its URL signs. Without the header, the
   * canonical request no longer matches the signature: 403 {@code SignatureDoesNotMatch} on AWS.
   */
  protected int missingSignedHeaderStatus() {
    return 403;
  }

  /** The S3 error code that goes with {@link #missingSignedHeaderStatus()}. */
  protected String missingSignedHeaderCode() {
    return "SignatureDoesNotMatch";
  }

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = backend();
    this.client = S3Client.create(vertx, TestResources.clientOptions(backend));
    // Only for the backend's TLS trust: every request names its host and port in its absolute URI.
    this.http = vertx.createHttpClient(backend.httpClientOptions());
    this.context = vertx.getOrCreateContext();
    client.createBucket(bucket).onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    if (client == null) {
      testContext.completeNow();
      return;
    }
    resources.cleanUp(client).eventually(client::close).eventually(http::close)
      .onComplete(testContext.succeedingThenComplete());
  }

  /** Upload through a presigned PUT, download through a presigned GET; the client sees the same object. */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 1024 * 1024})
  void putThenGetRoundTrip(int size, VertxTestContext testContext) {
    byte[] data = random(size);
    String key = resources.key(bucket, "round-trip/" + size + ".bin");
    S3Presigner presigner = client.presigner();
    send(HttpMethod.PUT, presigner.presignPut(bucket, key, 10, TimeUnit.MINUTES), Buffer.buffer(data), null)
      .compose(put -> {
        expectStatus(put, 200);
        return send(HttpMethod.GET, presigner.presignGet(bucket, key, Duration.ofMinutes(10)), null, null);
      })
      .compose(get -> {
        expectStatus(get, 200);
        assertArrayEquals(data, get.body.getBytes());
        return client.getObject(bucket, key);
      })
      .onComplete(testContext.succeeding(body -> testContext.verify(() -> {
        assertArrayEquals(data, body.getBytes());
        testContext.completeNow();
      })));
  }

  /** Only {@code Host} is signed: the uploader chooses the content type, and S3 stores and serves it. */
  @Test
  void uploaderChoosesTheContentType(VertxTestContext testContext) {
    String key = resources.key(bucket, "content-type/image.png");
    S3Presigner presigner = presigner();
    send(HttpMethod.PUT, presigner.presignPut(bucket, key, 10, TimeUnit.MINUTES), Buffer.buffer("png"), "image/png")
      .compose(put -> {
        expectStatus(put, 200);
        return client.headObject(bucket, key);
      })
      .compose(head -> {
        assertEquals("image/png", head.getContentType());
        return send(HttpMethod.GET, presigner.presignGet(bucket, key, 10, TimeUnit.MINUTES), null, null);
      })
      .onComplete(testContext.succeeding(get -> testContext.verify(() -> {
        expectStatus(get, 200);
        assertEquals("image/png", get.contentType);
        assertEquals("png", get.body.toString());
        testContext.completeNow();
      })));
  }

  /** The key is encoded once in the URL and signed as sent; the client finds the object under the same key. */
  @ParameterizedTest
  @ValueSource(strings = {"dir/sub/file.txt", "a b+c=d&e.txt", "ünïcødé/文件.bin", "مستند/ملف.txt", "percent%20literal",
    "question?mark#hash", "tilde~star*(paren)!", "semi;colon,comma@at$dollar", "quote'single\"double",
    "brackets[]{}^|`<>"})
  void specialCharacterKeys(String key, VertxTestContext testContext) {
    resources.key(bucket, key);
    Buffer content = Buffer.buffer("content of " + key);
    S3Presigner presigner = presigner();
    send(HttpMethod.PUT, presigner.presignPut(bucket, key, 10, TimeUnit.MINUTES), content, null)
      .compose(put -> {
        expectStatus(put, 200);
        return client.getObject(bucket, key);
      })
      .compose(body -> {
        assertEquals(content, body);
        return send(HttpMethod.GET, presigner.presignGet(bucket, key, 10, TimeUnit.MINUTES), null, null);
      })
      .onComplete(testContext.succeeding(get -> testContext.verify(() -> {
        expectStatus(get, 200);
        assertEquals(content, get.body);
        testContext.completeNow();
      })));
  }

  /**
   * Signed two days ago (through the {@code Clock} hook): a one-hour URL has expired, for GET and
   * PUT, while a seven-day URL signed at the same time is still valid. The expired PUT stores
   * nothing.
   */
  @Test
  void expiryIsCountedFromSigningTime(VertxTestContext testContext) {
    String key = resources.key(bucket, "expiry/existing.txt");
    String notCreated = resources.key(bucket, "expiry/not-created.txt");
    Clock twoDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC);
    S3Presigner past = new S3PresignerImpl(ClientConfig.from(TestResources.clientOptions(backend)), twoDaysAgo);
    client.putObject(bucket, key, Buffer.buffer("still here"))
      .compose(etag -> send(HttpMethod.GET, past.presignGet(bucket, key, 1, TimeUnit.HOURS), null, null))
      .compose(expiredGet -> {
        expectError(expiredGet, 403, "AccessDenied");
        return send(HttpMethod.PUT, past.presignPut(bucket, notCreated, 1, TimeUnit.HOURS), Buffer.buffer("x"), null);
      })
      .compose(expiredPut -> {
        expectError(expiredPut, 403, "AccessDenied");
        return send(HttpMethod.GET, past.presignGet(bucket, key, 7, TimeUnit.DAYS), null, null);
      })
      .compose(validGet -> {
        expectStatus(validGet, 200);
        assertEquals("still here", validGet.body.toString());
        return exists(notCreated);
      })
      .onComplete(testContext.succeeding(created -> testContext.verify(() -> {
        assertFalse(created, "the expired PUT stored nothing");
        testContext.completeNow();
      })));
  }

  /**
   * Any change to a signed part of the URL is rejected, and so is the bare URL: the object isn't
   * public. Using a GET URL to PUT doesn't overwrite the object.
   */
  @ParameterizedTest
  @ValueSource(strings = {"signature", "key", "expires", "method", "no-query"})
  void tamperedUrlsAreRejected(String tampering, VertxTestContext testContext) {
    String key = resources.key(bucket, "tamper/target.txt");
    String other = resources.key(bucket, "tamper/other.txt");
    String url = presigner().presignGet(bucket, key, 3600, TimeUnit.SECONDS);
    HttpMethod method = HttpMethod.GET;
    String tampered;
    Buffer body = null;
    switch (tampering) {
      case "signature":
        char last = url.charAt(url.length() - 1);
        tampered = url.substring(0, url.length() - 1) + (last == '0' ? '1' : '0');
        break;
      case "key":
        tampered = replaceOnce(url, "/tamper/target.txt?", "/tamper/other.txt?");
        break;
      case "expires":
        tampered = replaceOnce(url, "X-Amz-Expires=3600&", "X-Amz-Expires=3601&");
        break;
      case "method":
        method = HttpMethod.PUT;
        tampered = url;
        body = Buffer.buffer("overwritten");
        break;
      case "no-query":
        tampered = url.substring(0, url.indexOf('?'));
        break;
      default:
        throw new IllegalArgumentException(tampering);
    }
    HttpMethod sentMethod = method;
    Buffer sentBody = body;
    String expectedCode = "no-query".equals(tampering) ? "AccessDenied" : "SignatureDoesNotMatch";
    client.putObject(bucket, key, Buffer.buffer("original"))
      .compose(etag -> send(sentMethod, tampered, sentBody, null))
      .compose(response -> {
        expectError(response, 403, expectedCode);
        return client.getObject(bucket, key);
      })
      .compose(stored -> {
        assertEquals("original", stored.toString());
        return exists(other);
      })
      .onComplete(testContext.succeeding(otherExists -> testContext.verify(() -> {
        assertFalse(otherExists);
        testContext.completeNow();
      })));
  }

  /** A valid URL for a missing key passes authentication and gets the server's 404. */
  @Test
  void validUrlForAMissingKeyGivesNoSuchKey(VertxTestContext testContext) {
    String url = presigner().presignGet(bucket, "missing/never-written.txt", 10, TimeUnit.MINUTES);
    send(HttpMethod.GET, url, null, null).onComplete(testContext.succeeding(response -> testContext.verify(() -> {
      expectError(response, 404, "NoSuchKey");
      testContext.completeNow();
    })));
  }

  /**
   * A standalone presigner whose endpoint is an address that is never contacted (an in-cluster
   * one) signs URLs for its public endpoint, and they work.
   */
  @Test
  void standalonePresignerUsesThePublicEndpoint(VertxTestContext testContext) {
    S3Presigner presigner = S3Presigner.create(behindAnInternalEndpoint());
    roundTripThrough(presigner, resources.key(bucket, "public-endpoint/standalone.txt"))
      .onComplete(testContext.succeedingThenComplete());
  }

  /** As {@link #standalonePresignerUsesThePublicEndpoint}, through {@code S3Client.presigner()}. */
  @Test
  void clientPresignerUsesThePublicEndpoint(VertxTestContext testContext) {
    S3Client internal = S3Client.create(vertx, behindAnInternalEndpoint());
    roundTripThrough(internal.presigner(), resources.key(bucket, "public-endpoint/client.txt"))
      .eventually(internal::close)
      .onComplete(testContext.succeedingThenComplete());
  }

  // --- options --------------------------------------------------------------------------------

  /**
   * A signed {@code Content-Type} must be sent exactly as signed. The exact value is stored; another
   * value, the same value with a charset parameter (which some HTTP clients add to string bodies),
   * or no header at all is rejected, and nothing is stored. A missing header gets the backend's
   * {@link #missingSignedHeaderStatus()} and {@link #missingSignedHeaderCode()}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"exact", "other", "with-charset", "missing"})
  void signedContentTypeMustBeSentExactly(String sent, VertxTestContext testContext) {
    String key = resources.key(bucket, "signed-type/" + sent + ".jpg");
    S3PresignedRequest upload = presigner().presignPut(bucket, key, 10, TimeUnit.MINUTES,
      new S3PresignPutOptions().setContentType("image/jpeg"));
    assertEquals(Map.of("Content-Type", "image/jpeg"), upload.getHeaders());
    Map<String, String> headers;
    switch (sent) {
      case "exact":
        headers = upload.getHeaders();
        break;
      case "other":
        headers = Map.of("Content-Type", "image/png");
        break;
      case "with-charset":
        headers = Map.of("Content-Type", "image/jpeg; charset=utf-8");
        break;
      default:
        headers = Map.of();
    }
    boolean accepted = "exact".equals(sent);
    sendWithHeaders(HttpMethod.valueOf(upload.getMethod()), upload.getUrl(), Buffer.buffer("jpeg bytes"), headers)
      .compose(response -> {
        if (!accepted) {
          if ("missing".equals(sent)) {
            expectError(response, missingSignedHeaderStatus(), missingSignedHeaderCode());
          } else {
            expectError(response, 403, "SignatureDoesNotMatch");
          }
          return exists(key);
        }
        expectStatus(response, 200);
        return client.headObject(bucket, key).map(head -> {
          assertEquals("image/jpeg", head.getContentType());
          return true;
        });
      })
      .onComplete(testContext.succeeding(stored -> testContext.verify(() -> {
        assertEquals(accepted, stored);
        testContext.completeNow();
      })));
  }

  /**
   * A signed {@code Content-MD5} pins the exact bytes: a header with another digest or no header is
   * rejected by the signature check, and the signed digest with other bytes of the same length is
   * rejected by the server's body check. Only the matching upload is stored.
   */
  @ParameterizedTest
  @ValueSource(strings = {"matching", "other-md5", "missing", "other-body"})
  void signedContentMd5PinsTheBytes(String sent, VertxTestContext testContext) {
    String key = resources.key(bucket, "signed-md5/" + sent + ".bin");
    Buffer body = Buffer.buffer("exact bytes");
    Buffer other = Buffer.buffer("Exact bytes");
    S3PresignedRequest upload = presigner().presignPut(bucket, key, 10, TimeUnit.MINUTES,
      new S3PresignPutOptions().setContentMd5(md5(body)));
    assertEquals(Map.of("Content-MD5", md5(body)), upload.getHeaders());
    Future<Response> attempt;
    switch (sent) {
      case "matching":
        attempt = sendWithHeaders(HttpMethod.PUT, upload.getUrl(), body, upload.getHeaders());
        break;
      case "other-md5":
        attempt = sendWithHeaders(HttpMethod.PUT, upload.getUrl(), other, Map.of("Content-MD5", md5(other)));
        break;
      case "missing":
        attempt = sendWithHeaders(HttpMethod.PUT, upload.getUrl(), body, Map.of());
        break;
      default:
        attempt = sendWithHeaders(HttpMethod.PUT, upload.getUrl(), other, upload.getHeaders());
    }
    attempt
      .compose(response -> {
        switch (sent) {
          case "matching":
            expectStatus(response, 200);
            break;
          case "other-body":
            expectError(response, 400, "BadDigest");
            break;
          case "missing":
            expectError(response, missingSignedHeaderStatus(), missingSignedHeaderCode());
            break;
          default:
            expectError(response, 403, "SignatureDoesNotMatch");
        }
        return exists(key);
      })
      .onComplete(testContext.succeeding(stored -> testContext.verify(() -> {
        assertEquals("matching".equals(sent), stored);
        testContext.completeNow();
      })));
  }

  /**
   * GET overrides replace the download's response headers, including a Content-Disposition for a
   * non-ASCII file name; the stored object is unchanged, so a URL without overrides still gets the
   * stored headers.
   */
  @Test
  void getOverridesReplaceTheResponseHeaders(VertxTestContext testContext) {
    String key = resources.key(bucket, "overrides/invoice.pdf");
    String disposition = S3PresignGetOptions.attachment("فاتورة 7.pdf");
    S3PresignedRequest download = presigner().presignGet(bucket, key, 10, TimeUnit.MINUTES, new S3PresignGetOptions()
      .setContentType("application/pdf").setContentDisposition(disposition).setCacheControl("no-store"));
    assertEquals("GET", download.getMethod());
    assertEquals(Map.of(), download.getHeaders());
    client.putObject(bucket, key, Buffer.buffer("%PDF-1.7"), new S3PutObjectOptions().setContentType("application/octet-stream"))
      .compose(etag -> sendWithHeaders(HttpMethod.GET, download.getUrl(), null, Map.of()))
      .compose(response -> {
        expectStatus(response, 200);
        assertEquals("%PDF-1.7", response.body.toString());
        assertEquals("application/pdf", response.headers.get("Content-Type"));
        assertEquals(disposition, response.headers.get("Content-Disposition"));
        assertEquals("no-store", response.headers.get("Cache-Control"));
        return sendWithHeaders(HttpMethod.GET, presigner().presignGet(bucket, key, 10, TimeUnit.MINUTES), null, Map.of());
      })
      .onComplete(testContext.succeeding(plain -> testContext.verify(() -> {
        expectStatus(plain, 200);
        assertEquals("application/octet-stream", plain.headers.get("Content-Type"));
        assertNull(plain.headers.get("Content-Disposition"));
        testContext.completeNow();
      })));
  }

  // --- helpers --------------------------------------------------------------------------------

  /** Whether {@code key} exists in the suite's bucket, according to the client. */
  private Future<Boolean> exists(String key) {
    return client.headObject(bucket, key).transform(ar -> {
      if (ar.succeeded()) {
        return Future.succeededFuture(true);
      }
      if (ar.cause() instanceof S3ServiceException && ((S3ServiceException) ar.cause()).getStatusCode() == 404) {
        return Future.succeededFuture(false);
      }
      return Future.failedFuture(ar.cause());
    });
  }

  /** A presigner with the backend's options, as an application would create one. */
  private S3Presigner presigner() {
    return S3Presigner.create(TestResources.clientOptions(backend));
  }

  /** The backend as the public endpoint, behind an {@link #INTERNAL_ENDPOINT} that is never contacted. */
  private S3ClientOptions behindAnInternalEndpoint() {
    return TestResources.clientOptions(backend)
      .setEndpoint(INTERNAL_ENDPOINT)
      .setPublicEndpoint(backend.endpoint().toString());
  }

  /** PUT and GET through {@code presigner}'s URLs, which must point to the backend; checks with the client. */
  private Future<Void> roundTripThrough(S3Presigner presigner, String key) {
    String put = presigner.presignPut(bucket, key, 10, TimeUnit.MINUTES);
    String get = presigner.presignGet(bucket, key, 10, TimeUnit.MINUTES);
    String backendHost = backend.endpoint().getHost();
    assertTrue(put.contains(backendHost + "/") || put.contains(backendHost + ":"), "points to the backend");
    assertFalse(put.contains("internal.invalid") || get.contains("internal.invalid"), "never to the internal endpoint");
    return send(HttpMethod.PUT, put, Buffer.buffer("via " + key), null)
      .compose(response -> {
        expectStatus(response, 200);
        return send(HttpMethod.GET, get, null, null);
      })
      .compose(response -> {
        expectStatus(response, 200);
        assertEquals("via " + key, response.body.toString());
        return client.getObject(bucket, key);
      })
      .map(body -> {
        assertEquals("via " + key, body.toString());
        return null;
      });
  }

  /**
   * Sends {@code url} as is, without credentials. The whole exchange is assembled in one task on a
   * Vert.x context, so the response handlers are attached before the response can arrive.
   */
  private Future<Response> send(HttpMethod method, String url, Buffer body, String contentType) {
    return sendWithHeaders(method, url, body, contentType == null ? Map.of() : Map.of("Content-Type", contentType));
  }

  /** As {@link #send(HttpMethod, String, Buffer, String)}, with exactly {@code headers}. */
  private Future<Response> sendWithHeaders(HttpMethod method, String url, Buffer body, Map<String, String> headers) {
    RequestOptions options = new RequestOptions().setMethod(method).setAbsoluteURI(url);
    headers.forEach(options::putHeader);
    Promise<Response> promise = Promise.promise();
    context.runOnContext(v -> http.request(options)
      .compose(request -> body == null ? request.send() : request.send(body))
      .compose(response -> response.body()
        .map(content -> new Response(response.statusCode(), response.headers(), content)))
      .onComplete(promise));
    return promise.future();
  }

  /** The {@code Content-MD5} value of {@code body}: the base64 MD5 digest. */
  private static String md5(Buffer body) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body.getBytes()));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static void expectStatus(Response response, int status) {
    assertEquals(status, response.status, response.body.toString());
  }

  private static void expectError(Response response, int status, String code) {
    expectStatus(response, status);
    Matcher matcher = ERROR_CODE.matcher(response.body.toString());
    assertTrue(matcher.find(), response.body.toString());
    assertEquals(code, matcher.group(1), response.body.toString());
  }

  private static String replaceOnce(String url, String target, String replacement) {
    int at = url.indexOf(target);
    assertTrue(at >= 0 && url.indexOf(target, at + 1) < 0, target);
    return url.substring(0, at) + replacement + url.substring(at + target.length());
  }

  private static byte[] random(int size) {
    byte[] data = new byte[size];
    new Random(size).nextBytes(data);
    return data;
  }

  private static final class Response {
    final int status;
    final MultiMap headers;
    final String contentType;
    final Buffer body;

    Response(int status, MultiMap headers, Buffer body) {
      this.status = status;
      this.headers = headers;
      this.contentType = headers.get("Content-Type");
      this.body = body;
    }
  }
}
