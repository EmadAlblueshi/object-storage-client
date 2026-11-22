package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3PresignGetOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * The public presigner: URL shape, endpoints and addressing styles, expiry conversion and
 * validation, argument checks, and the client's accessor. Signing itself is covered by
 * {@link S3SignatureV4PresignTest}; URLs are checked against real servers by the integration
 * suite. Time comes from a fixed {@link Clock} through the impl test hook.
 */
@ExtendWith(VertxExtension.class)
class S3PresignerTest {

  private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-21T10:15:30Z"), ZoneOffset.UTC);

  @Test
  void awsPublishedExampleThroughThePublicApi() {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://s3.amazonaws.com")
      .setRegion("us-east-1")
      .setPathStyleAccess(false)
      .setCredentials(new S3Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
    S3Presigner presigner = presigner(options, Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC));
    assertEquals("https://examplebucket.s3.amazonaws.com/test.txt"
      + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
      + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
      + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
      + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
      presigner.presignGet("examplebucket", "test.txt", 86400, TimeUnit.SECONDS));
  }

  @Test
  void pathStyleUrlKeepsTheNonDefaultPortAndEncodesTheKeyOnce() {
    String url = presigner(local(), NOW).presignPut("my-bucket", "ملف 1+1/a b.txt", 1, TimeUnit.HOURS);
    assertTrue(url.startsWith("http://localhost:9000/my-bucket/%D9%85%D9%84%D9%81%201%2B1/a%20b.txt?"
      + "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20261121%2Ftest-west-1%2Fs3%2Faws4_request"
      + "&X-Amz-Date=20261121T101530Z&X-Amz-Expires=3600&X-Amz-Security-Token=token&X-Amz-SignedHeaders=host"
      + "&X-Amz-Signature="), url);
  }

  @Test
  void urlsPointToThePublicEndpoint() {
    S3ClientOptions options = local().setEndpoint("http://minio.storage.svc:9000")
      .setPublicEndpoint("https://files.example.com").setPathStyleAccess(false);
    String url = presigner(options, NOW).presignGet("my-bucket", "a.txt", 60, TimeUnit.SECONDS);
    assertTrue(url.startsWith("https://my-bucket.files.example.com/a.txt?"), url);
    assertFalse(url.contains("minio.storage.svc"), url);
  }

  @Test
  void theUrlCarriesExactlyWhatTheSignerSigned() {
    ClientConfig config = ClientConfig.from(local());
    S3SignatureV4.Presigned expected = new S3SignatureV4(config.region, NOW).presign(new S3SignatureV4.Request(
        "GET", "localhost:9000", "/my-bucket/a b", java.util.List.of(), java.util.List.of(),
        S3SignatureV4.UNSIGNED_PAYLOAD),
      300, "AKIDEXAMPLE", "secret", "token");
    assertEquals("http://localhost:9000/my-bucket/a%20b?" + expected.query,
      new S3PresignerImpl(config, NOW).presignGet("my-bucket", "a b", 5, TimeUnit.MINUTES));
  }

  @Test
  void sessionTokenIsIncluded() {
    assertTrue(presigner(local(), NOW).presignGet("my-bucket", "a", 60, TimeUnit.SECONDS)
      .contains("&X-Amz-Security-Token=token&"));
  }

  // --- expiry ---------------------------------------------------------------------------------------------

  @Test
  void equivalentExpiriesGiveTheSameUrl() {
    S3Presigner presigner = presigner(local(), NOW);
    String url = presigner.presignGet("my-bucket", "a", 3600, TimeUnit.SECONDS);
    assertEquals(url, presigner.presignGet("my-bucket", "a", 60, TimeUnit.MINUTES));
    assertEquals(url, presigner.presignGet("my-bucket", "a", 1, TimeUnit.HOURS));
    assertEquals(url, presigner.presignGet("my-bucket", "a", 3_600_000, TimeUnit.MILLISECONDS));
    assertEquals(url, presigner.presignGet("my-bucket", "a", 3_600_000_000_000L, TimeUnit.NANOSECONDS));
    assertEquals(url, presigner.presignGet("my-bucket", "a", Duration.ofHours(1)));
    assertEquals(presigner.presignPut("my-bucket", "a", 1, TimeUnit.HOURS),
      presigner.presignPut("my-bucket", "a", Duration.ofMinutes(60)));
  }

  @Test
  void sevenDaysIsTheLongestExpiry() {
    S3Presigner presigner = presigner(local(), NOW);
    assertTrue(presigner.presignGet("my-bucket", "a", 7, TimeUnit.DAYS).contains("X-Amz-Expires=604800&"));
    assertTrue(presigner.presignGet("my-bucket", "a", Duration.ofDays(7)).contains("X-Amz-Expires=604800&"));
    assertTrue(presigner.presignGet("my-bucket", "a", 1, TimeUnit.SECONDS).contains("X-Amz-Expires=1&"));
  }

  @Test
  void invalidExpiriesAreRejected() {
    S3Presigner presigner = presigner(local(), NOW);
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 0, TimeUnit.SECONDS), "positive");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", -1, TimeUnit.HOURS), "positive");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 1500, TimeUnit.MILLISECONDS), "whole number of seconds");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 999, TimeUnit.MILLISECONDS), "whole number of seconds");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 1, TimeUnit.NANOSECONDS), "whole number of seconds");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 604801, TimeUnit.SECONDS), "at most 7 days");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", 8, TimeUnit.DAYS), "at most 7 days");
    assertRejected(() -> presigner.presignGet("my-bucket", "a", Long.MAX_VALUE, TimeUnit.DAYS), "at most 7 days");
    assertRejected(() -> presigner.presignPut("my-bucket", "a", Duration.ZERO), "positive");
    assertRejected(() -> presigner.presignPut("my-bucket", "a", Duration.ofSeconds(-1)), "positive");
    assertRejected(() -> presigner.presignPut("my-bucket", "a", Duration.ofMillis(1500)), "whole number of seconds");
    assertRejected(() -> presigner.presignPut("my-bucket", "a", Duration.ofSeconds(604801)), "at most 7 days");
  }

  // --- arguments ------------------------------------------------------------------------------------------

  @Test
  void nullArgumentsThrowNullPointerException() {
    S3Presigner presigner = presigner(local(), NOW);
    assertThrows(NullPointerException.class, () -> presigner.presignGet(null, "a", 1, TimeUnit.HOURS));
    assertThrows(NullPointerException.class, () -> presigner.presignGet("my-bucket", null, 1, TimeUnit.HOURS));
    assertThrows(NullPointerException.class, () -> presigner.presignGet("my-bucket", "a", 1, null));
    assertThrows(NullPointerException.class, () -> presigner.presignPut("my-bucket", "a", null));
    assertThrows(NullPointerException.class, () -> S3Presigner.create(null));
  }

  @Test
  void invalidBucketsAndKeysThrowIllegalArgumentException() {
    S3Presigner presigner = presigner(local(), NOW);
    assertRejected(() -> presigner.presignGet("My_Bucket", "a", 1, TimeUnit.HOURS), "Invalid bucket name");
    assertRejected(() -> presigner.presignGet("my-bucket", "", 1, TimeUnit.HOURS), "must not be empty");
    assertRejected(() -> presigner.presignGet("my-bucket", "k".repeat(1025), 1, TimeUnit.HOURS), "longer than 1024");
  }

  @Test
  void dottedBucketsFollowThePublicEndpointsScheme() {
    S3ClientOptions options = local().setEndpoint("http://minio.storage.svc:9000").setPathStyleAccess(false);
    assertRejected(() -> presigner(new S3ClientOptions(options).setPublicEndpoint("https://files.example.com"), NOW)
      .presignGet("my.bucket", "a", 1, TimeUnit.HOURS), "contains dots");
    assertTrue(presigner(new S3ClientOptions(options).setPublicEndpoint("http://files.example.com"), NOW)
      .presignGet("my.bucket", "a", 1, TimeUnit.HOURS).startsWith("http://my.bucket.files.example.com/a?"));
  }

  @Test
  void createValidatesTheOptionsWithoutAVertxInstance() {
    assertInstanceOf(S3PresignerImpl.class, S3Presigner.create(local()));
    assertRejected(() -> S3Presigner.create(local().setCredentials(null)), "credentials are required");
    assertRejected(() -> S3Presigner.create(local().setPublicEndpoint("ftp://x")), "publicEndpoint");
  }

  @Test
  void optionsAreCopied() {
    S3ClientOptions options = local();
    S3Presigner presigner = S3Presigner.create(options);
    options.setEndpoint("http://other:1234").getCredentials().setAccessKey("CHANGED");
    String url = presigner.presignGet("my-bucket", "a", 1, TimeUnit.HOURS);
    assertTrue(url.startsWith("http://localhost:9000/") && url.contains("AKIDEXAMPLE"), url);
  }

  // --- the client's accessor -----------------------------------------------------------------------------

  @Test
  void clientPresignerIsCachedUsesThePublicEndpointAndOutlivesTheClient(Vertx vertx, VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, local().setPublicEndpoint("http://127.0.0.1:9000"));
    S3Presigner presigner = client.presigner();
    assertSame(presigner, client.presigner());
    client.close().onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      assertTrue(presigner.presignGet("my-bucket", "a", 1, TimeUnit.HOURS).startsWith("http://127.0.0.1:9000/my-bucket/a?"));
      testContext.completeNow();
    })));
  }

  // --- options ----------------------------------------------------------------------------------------------

  private static final String MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";

  /** PUT options become signed headers: the URL carries exactly what the signer signed with them. */
  @Test
  void putOptionsAreSignedHeadersTheUploaderMustSend() {
    S3PresignedRequest request = presigner(local(), NOW).presignPut("my-bucket", "photos/a.jpg", 600, TimeUnit.SECONDS,
      new S3PresignPutOptions().setContentType("image/jpeg").setContentMd5(MD5));

    assertEquals("PUT", request.getMethod());
    assertEquals(Map.of("Content-Type", "image/jpeg", "Content-MD5", MD5), request.getHeaders());
    assertEquals(List.of("Content-Type", "Content-MD5"), List.copyOf(request.getHeaders().keySet()), "in a stable order");
    S3SignatureV4.Presigned signed = new S3SignatureV4("test-west-1", NOW).presign(
      new S3SignatureV4.Request("PUT", "localhost:9000", "/my-bucket/photos/a.jpg", List.of(),
        List.of(Map.entry("Content-Type", "image/jpeg"), Map.entry("Content-MD5", MD5)), S3SignatureV4.UNSIGNED_PAYLOAD),
      600, "AKIDEXAMPLE", "secret", "token");
    assertEquals("http://localhost:9000/my-bucket/photos/a.jpg?" + signed.query, request.getUrl());
    assertTrue(request.getUrl().contains("&X-Amz-SignedHeaders=content-md5%3Bcontent-type%3Bhost&"), request.getUrl());
    assertTrue(signed.canonicalRequest.contains("\ncontent-md5:" + MD5 + "\ncontent-type:image/jpeg\nhost:localhost:9000\n"),
      signed.canonicalRequest);
  }

  @Test
  void onlyTheHeadersThatAreSetAreSigned() {
    S3PresignedRequest request = presigner(local(), NOW).presignPut("my-bucket", "a.bin", 600, TimeUnit.SECONDS,
      new S3PresignPutOptions().setContentType("application/pdf"));
    assertEquals(Map.of("Content-Type", "application/pdf"), request.getHeaders());
    assertTrue(request.getUrl().contains("&X-Amz-SignedHeaders=content-type%3Bhost&"), request.getUrl());
  }

  /** GET options become signed response-* query parameters, in canonical order, with nothing to send. */
  @Test
  void getOptionsAreSignedResponseOverrides() {
    String disposition = S3PresignGetOptions.attachment("فاتورة 7.pdf");
    S3PresignedRequest request = presigner(local(), NOW).presignGet("my-bucket", "invoices/7.pdf", 300, TimeUnit.SECONDS,
      new S3PresignGetOptions().setContentType("application/pdf").setContentDisposition(disposition).setCacheControl("no-store"));

    assertEquals("GET", request.getMethod());
    assertEquals(Map.of(), request.getHeaders());
    List<Map.Entry<String, String>> overrides = List.of(Map.entry("response-cache-control", "no-store"),
      Map.entry("response-content-disposition", disposition), Map.entry("response-content-type", "application/pdf"));
    S3SignatureV4.Presigned signed = new S3SignatureV4("test-west-1", NOW).presign(
      new S3SignatureV4.Request("GET", "localhost:9000", "/my-bucket/invoices/7.pdf", overrides, List.of(),
        S3SignatureV4.UNSIGNED_PAYLOAD), 300, "AKIDEXAMPLE", "secret", "token");
    assertEquals("http://localhost:9000/my-bucket/invoices/7.pdf?" + signed.query, request.getUrl());
    assertTrue(request.getUrl().contains("&X-Amz-SignedHeaders=host&" + S3SignatureV4.canonicalQuery(overrides)
      + "&X-Amz-Signature="), request.getUrl());
  }

  @Test
  void noOptionsGiveTheSimpleUrl() {
    S3Presigner presigner = presigner(local(), NOW);
    String put = presigner.presignPut("my-bucket", "k", 60, TimeUnit.SECONDS);
    String get = presigner.presignGet("my-bucket", "k", 60, TimeUnit.SECONDS);
    assertEquals(put, presigner.presignPut("my-bucket", "k", 60, TimeUnit.SECONDS, null).getUrl());
    assertEquals(put, presigner.presignPut("my-bucket", "k", 60, TimeUnit.SECONDS, new S3PresignPutOptions()).getUrl());
    assertEquals(get, presigner.presignGet("my-bucket", "k", 60, TimeUnit.SECONDS, null).getUrl());
    assertEquals(get, presigner.presignGet("my-bucket", "k", 60, TimeUnit.SECONDS, new S3PresignGetOptions()).getUrl());
    assertEquals(Map.of(), presigner.presignPut("my-bucket", "k", 60, TimeUnit.SECONDS, null).getHeaders());
  }

  @Test
  void durationOverloadsWithOptionsMatchTheTimeUnitOnes() {
    S3Presigner presigner = presigner(local(), NOW);
    S3PresignPutOptions put = new S3PresignPutOptions().setContentType("image/png");
    S3PresignGetOptions get = new S3PresignGetOptions().setCacheControl("no-store");
    assertEquals(presigner.presignPut("b-1", "k", 2, TimeUnit.MINUTES, put).toJson(),
      presigner.presignPut("b-1", "k", Duration.ofMinutes(2), put).toJson());
    assertEquals(presigner.presignGet("b-1", "k", 2, TimeUnit.MINUTES, get).toJson(),
      presigner.presignGet("b-1", "k", Duration.ofMinutes(2), get).toJson());
  }

  @Test
  void invalidOptionsAreRejected() {
    S3Presigner presigner = presigner(local(), NOW);
    for (String contentType : new String[] {"", "image/é", "text/plain\r\nX-Evil: 1", "tab\there"}) {
      assertRejected(() -> presigner.presignPut("b-1", "k", 60, TimeUnit.SECONDS,
        new S3PresignPutOptions().setContentType(contentType)), "content type");
      assertRejected(() -> presigner.presignGet("b-1", "k", 60, TimeUnit.SECONDS,
        new S3PresignGetOptions().setContentType(contentType)), "content type");
    }
    // not base64, no padding, 15 and 17 bytes, non-canonical trailing bits, whitespace
    for (String md5 : new String[] {"not base64!", "1B2M2Y8AsgTpgAmY7PhCfg", "AAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAA=",
      "1B2M2Y8AsgTpgAmY7PhCfh==", " 1B2M2Y8AsgTpgAmY7PhCfg==", ""}) {
      assertRejected(() -> presigner.presignPut("b-1", "k", 60, TimeUnit.SECONDS,
        new S3PresignPutOptions().setContentMd5(md5)), "Content-MD5");
    }
    assertRejected(() -> presigner.presignGet("b-1", "k", 60, TimeUnit.SECONDS,
      new S3PresignGetOptions().setContentDisposition("attachment; filename=\"فاتورة.pdf\"")), "attachment or inline");
    assertRejected(() -> presigner.presignGet("b-1", "k", 60, TimeUnit.SECONDS,
      new S3PresignGetOptions().setContentDisposition("")), "content disposition");
    for (String cacheControl : new String[] {"", "no-store\r\nX-Evil: 1"}) {
      assertRejected(() -> presigner.presignGet("b-1", "k", 60, TimeUnit.SECONDS,
        new S3PresignGetOptions().setCacheControl(cacheControl)), "cache control");
    }
    // the bucket, key and expiry rules still apply
    assertRejected(() -> presigner.presignPut("B", "k", 60, TimeUnit.SECONDS, new S3PresignPutOptions()), "");
    assertRejected(() -> presigner.presignGet("b-1", "k", 0, TimeUnit.SECONDS, new S3PresignGetOptions()), "positive");
  }

  // --- helpers --------------------------------------------------------------------------------------------

  private static S3ClientOptions local() {
    return new S3ClientOptions()
      .setEndpoint("http://localhost:9000")
      .setRegion("test-west-1")
      .setCredentials(new S3Credentials("AKIDEXAMPLE", "secret").setSessionToken("token"));
  }

  private static S3Presigner presigner(S3ClientOptions options, Clock clock) {
    return new S3PresignerImpl(ClientConfig.from(options), clock);
  }

  private static void assertRejected(org.junit.jupiter.api.function.Executable call, String messagePart) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
    assertTrue(e.getMessage().contains(messagePart), e.getMessage());
  }
}
