package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Query-string presigning ({@link S3SignatureV4#presign}), against the example AWS publishes in
 * "Authenticating Requests: Using Query Parameters (AWS Signature Version 4)" and the S3 rules the
 * example doesn't exercise. Time comes from a fixed {@link Clock}.
 */
class S3SignatureV4PresignTest {

  // --- the AWS published example ---------------------------------------------------------------------

  private static final String AWS_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
  private static final String AWS_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final Clock AWS_TIME = Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void awsPublishedGetExample() {
    S3SignatureV4 signer = new S3SignatureV4("us-east-1", AWS_TIME);
    S3SignatureV4.Presigned presigned = signer.presign(
      new S3SignatureV4.Request("GET", "examplebucket.s3.amazonaws.com", "/test.txt", List.of(), List.of(),
        S3SignatureV4.UNSIGNED_PAYLOAD),
      86400, AWS_ACCESS_KEY, AWS_SECRET_KEY, null);

    assertEquals("GET\n"
      + "/test.txt\n"
      + "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
      + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host\n"
      + "host:examplebucket.s3.amazonaws.com\n"
      + "\n"
      + "host\n"
      + "UNSIGNED-PAYLOAD", presigned.canonicalRequest);
    assertEquals("AWS4-HMAC-SHA256\n"
      + "20130524T000000Z\n"
      + "20130524/us-east-1/s3/aws4_request\n"
      + "3bfa292879f6447bbcda7001decf97f4a54dc650c8942174ae0a9121cf58ad04", presigned.stringToSign);
    assertEquals("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", presigned.signature);
    assertEquals("X-Amz-Algorithm=AWS4-HMAC-SHA256"
      + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
      + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
      + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", presigned.query);
  }

  // --- S3 rules beyond the example ----------------------------------------------------------------------

  private static final String ACCESS_KEY = "AKIDEXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
  private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-21T10:15:30.789Z"), ZoneOffset.UTC);

  @Test
  void sessionTokenIsASignedQueryParameter() {
    S3SignatureV4.Presigned presigned = signer().presign(request("PUT", "localhost:9000", "/bucket/key"), 3600,
      ACCESS_KEY, SECRET_KEY, "token/with+special=chars");
    String encodedToken = "X-Amz-Security-Token=token%2Fwith%2Bspecial%3Dchars";
    assertTrue(canonicalQueryLine(presigned).contains("&" + encodedToken), presigned.canonicalRequest);
    assertTrue(presigned.query.contains(encodedToken), presigned.query);
    assertTrue(presigned.canonicalRequest.endsWith("\n\nhost\nUNSIGNED-PAYLOAD"), "only host is signed");
  }

  @Test
  void withoutSessionTokenThereIsNoTokenParameter() {
    assertFalse(signer().presign(request("GET", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY, SECRET_KEY, "")
      .query.contains("X-Amz-Security-Token"));
  }

  @Test
  void hostIncludesANonDefaultPortAndTheDateHasNoMilliseconds() {
    S3SignatureV4.Presigned presigned = signer().presign(request("GET", "localhost:9000", "/bucket/key"), 60,
      ACCESS_KEY, SECRET_KEY, null);
    assertTrue(presigned.canonicalRequest.contains("\nhost:localhost:9000\n"), presigned.canonicalRequest);
    assertTrue(presigned.query.contains("X-Amz-Date=20261121T101530Z&"), presigned.query);
    assertTrue(presigned.query.contains("X-Amz-Credential=AKIDEXAMPLE%2F20261121%2Fkw-west-1%2Fs3%2Faws4_request&"),
      presigned.query);
  }

  @Test
  void keyIsEncodedOnceKeepingSlashesWithoutNormalisation() {
    S3SignatureV4.Presigned presigned = signer().presign(
      request("GET", "localhost:9000", "/bucket/مرحبا world+%/./../a b.txt"), 60,
      ACCESS_KEY, SECRET_KEY, null);
    assertEquals("/bucket/%D9%85%D8%B1%D8%AD%D8%A8%D8%A7%20world%2B%25/./../a%20b.txt",
      presigned.canonicalRequest.split("\n")[1]);
  }

  @Test
  void queryIsTheSignedCanonicalQueryFollowedByTheSignature() {
    S3SignatureV4.Presigned presigned = signer().presign(request("PUT", "localhost:9000", "/bucket/key"), 900,
      ACCESS_KEY, SECRET_KEY, "token");
    assertEquals(canonicalQueryLine(presigned) + "&X-Amz-Signature=" + presigned.signature, presigned.query);
    assertTrue(presigned.signature.matches("[0-9a-f]{64}"), presigned.signature);
  }

  @Test
  void callerQueryParametersAreSignedAndSorted() {
    S3SignatureV4.Presigned presigned = signer().presign(new S3SignatureV4.Request("GET", "localhost:9000",
        "/bucket/key", List.of(Map.entry("response-content-type", "text/plain")), List.of(),
        S3SignatureV4.UNSIGNED_PAYLOAD),
      60, ACCESS_KEY, SECRET_KEY, null);
    assertTrue(canonicalQueryLine(presigned).endsWith("&X-Amz-SignedHeaders=host&response-content-type=text%2Fplain"),
      presigned.canonicalRequest);
  }

  @Test
  void methodAndExpiryChangeTheSignature() {
    String get = signer().presign(request("GET", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY, SECRET_KEY, null)
      .signature;
    assertFalse(get.equals(signer().presign(request("PUT", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY,
      SECRET_KEY, null).signature));
    assertFalse(get.equals(signer().presign(request("GET", "localhost:9000", "/bucket/key"), 61, ACCESS_KEY,
      SECRET_KEY, null).signature));
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 604800})
  void expiryBoundsAreAccepted(long seconds) {
    assertTrue(signer().presign(request("GET", "localhost:9000", "/bucket/key"), seconds, ACCESS_KEY, SECRET_KEY, null)
      .query.contains("X-Amz-Expires=" + seconds + "&"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, 604801, Long.MAX_VALUE})
  void expiryOutOfRangeIsRejected(long seconds) {
    assertThrows(IllegalArgumentException.class,
      () -> signer().presign(request("GET", "localhost:9000", "/bucket/key"), seconds, ACCESS_KEY, SECRET_KEY, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Amz-Signature", "x-amz-credential", "X-Amz-Expires", "X-Amz-Security-Token",
    "X-Amz-Date", "X-Amz-Algorithm", "X-Amz-SignedHeaders"})
  void signerManagedQueryParametersAreRejected(String name) {
    S3SignatureV4.Request request = new S3SignatureV4.Request("GET", "localhost:9000", "/bucket/key",
      List.of(Map.entry(name, "x")), List.of(), S3SignatureV4.UNSIGNED_PAYLOAD);
    assertThrows(IllegalArgumentException.class, () -> signer().presign(request, 60, ACCESS_KEY, SECRET_KEY, null));
  }

  @Test
  void aSignedPayloadHashIsRejected() {
    S3SignatureV4.Request request = new S3SignatureV4.Request("PUT", "localhost:9000", "/bucket/key", List.of(),
      List.of(), S3SignatureV4.EMPTY_PAYLOAD_SHA256);
    assertThrows(IllegalArgumentException.class, () -> signer().presign(request, 60, ACCESS_KEY, SECRET_KEY, null));
  }

  /** {@code sign} and {@code presign} use the same signing-key cache; interleaving them changes nothing. */
  @Test
  void interleavingSignAndPresignKeepsSignaturesCorrect() {
    S3SignatureV4 signer = signer();
    S3SignatureV4.Presigned first = signer.presign(request("GET", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY,
      SECRET_KEY, null);
    signer.sign(new S3SignatureV4.Request("GET", "localhost:9000", "/bucket/key", List.of(), List.of(),
      S3SignatureV4.EMPTY_PAYLOAD_SHA256), ACCESS_KEY, SECRET_KEY, null);
    // a fresh signer derives the key from scratch
    assertEquals(first.signature, signer().presign(request("GET", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY,
      SECRET_KEY, null).signature);
    assertEquals(first.signature, signer.presign(request("GET", "localhost:9000", "/bucket/key"), 60, ACCESS_KEY,
      SECRET_KEY, null).signature);
  }

  // --- helpers --------------------------------------------------------------------------------------------

  private static S3SignatureV4 signer() {
    return new S3SignatureV4("kw-west-1", NOW);
  }

  private static S3SignatureV4.Request request(String method, String host, String path) {
    return new S3SignatureV4.Request(method, host, path, List.of(), List.of(), S3SignatureV4.UNSIGNED_PAYLOAD);
  }

  private static String canonicalQueryLine(S3SignatureV4.Presigned presigned) {
    return presigned.canonicalRequest.split("\n")[2];
  }
}
