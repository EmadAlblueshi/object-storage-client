package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S3-specific signing rules and the audited fixes, on top of the generic AWS suite
 * ({@link S3SignatureV4TestSuiteTest}).
 */
class S3SignatureV4Test {

  private static final String ACCESS_KEY = "AKIDEXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
  private static final Instant NOW = Instant.parse("2026-11-20T10:15:30.123Z");

  // --- path: encoded once, '/' kept, no normalisation -------------------------------------------

  @Test
  void pathIsEncodedOnceKeepingSlashes() {
    assertEquals("/bucket/a%20b%2Bc%25~%C3%BC.txt", S3SignatureV4.encodePath("/bucket/a b+c%~ü.txt"));
    assertEquals("/bucket/%2A%28%29%27%21%40%24%26%3D%3B%3A%2C%3F%23%5B%5D",
      S3SignatureV4.encodePath("/bucket/*()'!@$&=;:,?#[]"));
  }

  @Test
  void pathIsNotNormalised() {
    assertEquals("/bucket/dir//x/./y/../z", S3SignatureV4.encodePath("/bucket/dir//x/./y/../z"));
    assertEquals("/", S3SignatureV4.encodePath("/"));
  }

  @Test
  void pathMustBeAbsolute() {
    assertThrows(IllegalArgumentException.class, () -> S3SignatureV4.encodePath("bucket/key"));
  }

  // --- query: encoded, sorted by name then value ------------------------------------------------

  @Test
  void queryIsSortedByNameThenValue() {
    assertEquals("a=1&a=2&b=1", S3SignatureV4.canonicalQuery(List.of(
      Map.entry("b", "1"), Map.entry("a", "2"), Map.entry("a", "1"))));
    // Name order differs from value order (the generic suite never distinguishes these).
    assertEquals("a=z&z=a", S3SignatureV4.canonicalQuery(List.of(Map.entry("z", "a"), Map.entry("a", "z"))));
  }

  @Test
  void queryNamesAndValuesAreFullyEncoded() {
    assertEquals("prefix=a%2Fb%20c%2Bd%2A~", S3SignatureV4.canonicalQuery(List.of(Map.entry("prefix", "a/b c+d*~"))));
    assertEquals("uploads=", S3SignatureV4.canonicalQuery(List.of(Map.entry("uploads", ""))));
    assertEquals("", S3SignatureV4.canonicalQuery(List.of()));
  }

  // --- host header ------------------------------------------------------------------------------

  @Test
  void hostHeaderIncludesOnlyNonDefaultPorts() {
    assertEquals("s3.example.com", S3SignatureV4.hostHeader("s3.example.com", 443, true));
    assertEquals("s3.example.com", S3SignatureV4.hostHeader("s3.example.com", -1, true));
    assertEquals("s3.example.com", S3SignatureV4.hostHeader("s3.example.com", 80, false));
    assertEquals("localhost:9000", S3SignatureV4.hostHeader("localhost", 9000, false));
    assertEquals("localhost:443", S3SignatureV4.hostHeader("localhost", 443, false));
    assertEquals("localhost:80", S3SignatureV4.hostHeader("localhost", 80, true));
    assertEquals("[::1]:9000", S3SignatureV4.hostHeader("::1", 9000, false));
    assertEquals("[::1]:9000", S3SignatureV4.hostHeader("[::1]", 9000, false));
  }

  // --- immutability and managed headers ----------------------------------------------------------

  @Test
  void inputsAreNotMutatedAndSigningIsRepeatable() {
    List<Map.Entry<String, String>> headers = new ArrayList<>(List.of(Map.entry("Content-Type", "text/plain")));
    List<Map.Entry<String, String>> query = new ArrayList<>(List.of(Map.entry("uploads", "")));
    S3SignatureV4.Request request = new S3SignatureV4.Request("POST", "localhost:9000", "/bucket/key", query, headers,
      S3SignatureV4.EMPTY_PAYLOAD_SHA256);
    S3SignatureV4 signer = signer(Clock.fixed(NOW, ZoneOffset.UTC));

    S3SignatureV4.Signed first = signer.sign(request, ACCESS_KEY, SECRET_KEY, null);
    S3SignatureV4.Signed second = signer.sign(request, ACCESS_KEY, SECRET_KEY, null);

    assertEquals(List.of(Map.entry("Content-Type", "text/plain")), headers);
    assertEquals(List.of(Map.entry("uploads", "")), query);
    assertEquals(first.authorization, second.authorization); // the b7dc1f2 reuse bug signed a stale Authorization
  }

  @ParameterizedTest
  @ValueSource(strings = { "Authorization", "x-amz-date", "X-Amz-Content-Sha256", "HOST", "X-Amz-Security-Token" })
  void signerManagedHeadersAreRejected(String name) {
    S3SignatureV4.Request request = request(List.of(Map.entry(name, "value")), S3SignatureV4.EMPTY_PAYLOAD_SHA256);
    assertThrows(IllegalArgumentException.class,
      () -> signer(Clock.fixed(NOW, ZoneOffset.UTC)).sign(request, ACCESS_KEY, SECRET_KEY, null));
  }

  // --- S3 headers ---------------------------------------------------------------------------------

  @Test
  void addsAndSignsS3Headers() {
    S3SignatureV4.Signed signed = signer(Clock.fixed(NOW, ZoneOffset.UTC))
      .sign(request(List.of(), S3SignatureV4.UNSIGNED_PAYLOAD), ACCESS_KEY, SECRET_KEY, null);

    assertEquals(List.of("X-Amz-Date", "X-Amz-Content-Sha256", "Authorization"), names(signed.headers));
    assertEquals("20261120T101530Z", signed.headers.get(0).getValue()); // truncated to seconds
    assertEquals(S3SignatureV4.UNSIGNED_PAYLOAD, signed.headers.get(1).getValue());
    assertTrue(signed.canonicalRequest.endsWith("\nhost;x-amz-content-sha256;x-amz-date\nUNSIGNED-PAYLOAD"),
      signed.canonicalRequest);
    assertTrue(signed.authorization.startsWith(
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261120/test-west-1/s3/aws4_request, "
        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="), signed.authorization);
  }

  @Test
  void signsSessionToken() {
    S3SignatureV4.Signed signed = signer(Clock.fixed(NOW, ZoneOffset.UTC))
      .sign(request(List.of(), S3SignatureV4.EMPTY_PAYLOAD_SHA256), ACCESS_KEY, SECRET_KEY, "token/with+chars");

    assertEquals(List.of("X-Amz-Date", "X-Amz-Content-Sha256", "X-Amz-Security-Token", "Authorization"),
      names(signed.headers));
    assertTrue(signed.canonicalRequest.contains("\nx-amz-security-token:token/with+chars\n"), signed.canonicalRequest);
    assertTrue(signed.authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token,"));
  }

  // --- signing-key cache: current entry only -------------------------------------------------------

  @Test
  void signingKeyCacheHoldsOnlyTheCurrentEntry() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    S3SignatureV4 signer = signer(clock);
    S3SignatureV4.Request request = request(List.of(), S3SignatureV4.EMPTY_PAYLOAD_SHA256);

    String sigA = signer.sign(request, ACCESS_KEY, "secret-A", null).signature;
    Object entryA = cachedEntry(signer);
    signer.sign(request, ACCESS_KEY, "secret-A", null);
    assertSame(entryA, cachedEntry(signer), "same day and secret must reuse the cached key");

    String sigB = signer.sign(request, ACCESS_KEY, "secret-B", null).signature;
    Object entryB = cachedEntry(signer);
    assertNotSame(entryA, entryB);
    assertEquals("secret-B", field(entryB, "secretKey"), "rotated-out secret must not be retained");
    assertNotEquals(sigA, sigB);

    clock.advance(Duration.ofDays(1));
    signer.sign(request, ACCESS_KEY, "secret-B", null);
    assertNotSame(entryB, cachedEntry(signer), "a new date derives a new key");
    assertEquals("20261121", field(cachedEntry(signer), "dateStamp"));
  }

  // --- thread safety ------------------------------------------------------------------------------

  @Test
  void concurrentSigningGivesConsistentResults() throws Exception {
    S3SignatureV4 signer = signer(Clock.fixed(NOW, ZoneOffset.UTC));
    List<S3SignatureV4.Request> requests = List.of(
      request(List.of(), S3SignatureV4.EMPTY_PAYLOAD_SHA256),
      request(List.of(Map.entry("Content-Type", "text/plain")), HashUtil.sha256Hex("hello")),
      new S3SignatureV4.Request("GET", "localhost:9000", "/bucket", List.of(Map.entry("list-type", "2")), List.of(),
        S3SignatureV4.EMPTY_PAYLOAD_SHA256));
    List<String> expected = new ArrayList<>();
    for (S3SignatureV4.Request r : requests) {
      expected.add(signer.sign(r, ACCESS_KEY, SECRET_KEY, null).signature);
    }

    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
    for (int t = 0; t < threads; t++) {
      Thread thread = new Thread(() -> {
        try {
          start.await();
          for (int i = 0; i < 500; i++) {
            int k = i % requests.size();
            String sig = signer.sign(requests.get(k), ACCESS_KEY, SECRET_KEY, null).signature;
            if (!sig.equals(expected.get(k))) {
              mismatches.add(k + ": " + sig);
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
      thread.setDaemon(true);
      thread.start();
    }
    start.countDown();
    assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "signing threads did not finish");
    assertTrue(mismatches.isEmpty(), () -> "inconsistent signatures: " + mismatches);
  }

  // --- HashUtil -------------------------------------------------------------------------------------

  @Test
  void hashUtilMatchesKnownValues() {
    assertEquals(S3SignatureV4.EMPTY_PAYLOAD_SHA256, HashUtil.sha256Hex(new byte[0]));
    assertEquals("00ff10ab", HashUtil.hex(new byte[] { 0x00, (byte) 0xff, 0x10, (byte) 0xab }));
    // RFC 4231, test case 1.
    byte[] key = new byte[20];
    java.util.Arrays.fill(key, (byte) 0x0b);
    assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
      HashUtil.hex(HashUtil.hmacSha256(key, "Hi There")));
    assertEquals(HashUtil.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)), HashUtil.sha256Hex("abc"));
  }

  // --- helpers --------------------------------------------------------------------------------------

  private static S3SignatureV4 signer(Clock clock) {
    return new S3SignatureV4("test-west-1", clock);
  }

  private static S3SignatureV4.Request request(List<Map.Entry<String, String>> headers, String payloadHash) {
    return new S3SignatureV4.Request("GET", "localhost:9000", "/bucket/key", List.of(), headers, payloadHash);
  }

  private static List<String> names(List<Map.Entry<String, String>> headers) {
    List<String> names = new ArrayList<>();
    headers.forEach(h -> names.add(h.getKey()));
    return names;
  }

  private static Object cachedEntry(S3SignatureV4 signer) throws Exception {
    return field(signer, "signingKey");
  }

  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
