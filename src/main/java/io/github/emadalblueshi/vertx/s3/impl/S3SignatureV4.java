package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 ({@code AWS4-HMAC-SHA256}) for S3, using the {@code Authorization}
 * header. Presigning (query-string auth) will be built on the same canonicalisation and
 * signing-key derivation in Phase 3.
 * <p>
 * Ported from {@code objectstorage.client.s3.impl.S3SignatureV4} (commit {@code b7dc1f2}), with the audited
 * fixes:
 * <ul>
 *   <li>The canonical query is sorted by encoded name, then encoded value, and every name and
 *   value is RFC 3986-encoded (the original kept insertion order).</li>
 *   <li>The canonical URI is the S3 path encoded <em>once</em>, keeping {@code /}, with no
 *   normalisation: {@code //}, {@code .} and {@code ..} are significant in S3 keys. The original
 *   signed the raw path.</li>
 *   <li>Inputs are never mutated. {@link #sign} returns the headers to add, and rejects requests
 *   that already carry signer-managed headers (the original wrote into the caller's options,
 *   so a reused options object signed its stale {@code Authorization} header).</li>
 *   <li>The Host value is supplied by the caller, and {@link #hostHeader} includes a non-default
 *   port (the original always signed the bare host).</li>
 *   <li>Canonical headers merge repeated names (values joined with {@code ,} in order), trim
 *   values and collapse whitespace runs to a single space.</li>
 *   <li>The derived signing key is cached, holding only the current entry, keyed by date and
 *   secret. A rotated-out secret is dropped as soon as a request is signed with the new one.</li>
 *   <li>Session tokens ({@code X-Amz-Security-Token}) are signed.</li>
 *   <li>The payload hash is an input: a hex SHA-256 or {@link #UNSIGNED_PAYLOAD}. How and on
 *   which thread the body is hashed is the transport's decision.</li>
 * </ul>
 * Thread-safe. Instances hold no per-request state.
 */
final class S3SignatureV4 {

  static final String ALGORITHM = "AWS4-HMAC-SHA256";
  static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
  static final String EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  static final String S3_SERVICE = "s3";

  static final String AUTHORIZATION = "Authorization";
  static final String X_AMZ_DATE = "X-Amz-Date";
  static final String X_AMZ_CONTENT_SHA256 = "X-Amz-Content-Sha256";
  static final String X_AMZ_SECURITY_TOKEN = "X-Amz-Security-Token";

  /** Headers the signer computes itself; callers must not pass them in. */
  private static final Set<String> MANAGED_HEADERS = Set.of(
    "host", "authorization", "x-amz-date", "x-amz-content-sha256", "x-amz-security-token");

  private static final String AWS4 = "AWS4";
  private static final String AWS4_REQUEST = "aws4_request";
  private static final DateTimeFormatter AMZ_DATE =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final String region;
  private final String service;
  private final Clock clock;
  private final boolean contentSha256Header;

  private volatile SigningKey signingKey;

  /** S3 signer for {@code region}: signs {@code X-Amz-Content-Sha256}, as S3 requires. */
  S3SignatureV4(String region, Clock clock) {
    this(region, S3_SERVICE, clock, true);
  }

  /**
   * @param contentSha256Header whether to add and sign {@code X-Amz-Content-Sha256}. S3 requires
   *     it. The generic AWS test suite (non-S3 services) runs without it.
   */
  S3SignatureV4(String region, String service, Clock clock, boolean contentSha256Header) {
    this.region = requireNonBlank(region, "region");
    this.service = requireNonBlank(service, "service");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.contentSha256Header = contentSha256Header;
  }

  /**
   * Signs {@code request}.
   *
   * @param sessionToken temporary-credentials session token, or {@code null}
   * @return the signature, plus the headers the caller must add to the request
   * @throws IllegalArgumentException if the request carries signer-managed headers or invalid values
   */
  Signed sign(Request request, String accessKey, String secretKey, String sessionToken) {
    Objects.requireNonNull(request, "request");
    requireNonBlank(accessKey, "accessKey");
    requireNonBlank(secretKey, "secretKey");

    String amzDate = AMZ_DATE.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    String dateStamp = amzDate.substring(0, 8);

    List<Map.Entry<String, String>> added = new ArrayList<>(4);
    added.add(Map.entry(X_AMZ_DATE, amzDate));
    if (contentSha256Header) {
      added.add(Map.entry(X_AMZ_CONTENT_SHA256, request.payloadHash));
    }
    if (sessionToken != null && !sessionToken.isEmpty()) {
      added.add(Map.entry(X_AMZ_SECURITY_TOKEN, sessionToken));
    }

    TreeMap<String, String> canonicalHeaders = canonicalHeaders(request, added);
    String signedHeaders = String.join(";", canonicalHeaders.keySet());

    StringBuilder canonical = new StringBuilder(256)
      .append(request.method).append('\n')
      .append(encodePath(request.path)).append('\n')
      .append(canonicalQuery(request.query)).append('\n');
    canonicalHeaders.forEach((name, value) -> canonical.append(name).append(':').append(value).append('\n'));
    canonical.append('\n').append(signedHeaders).append('\n').append(request.payloadHash);
    String canonicalRequest = canonical.toString();

    String scope = dateStamp + "/" + region + "/" + service + "/" + AWS4_REQUEST;
    String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + HashUtil.sha256Hex(canonicalRequest);
    String signature = HashUtil.hex(HashUtil.hmacSha256(signingKey(dateStamp, secretKey), stringToSign));
    String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope
      + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    added.add(Map.entry(AUTHORIZATION, authorization));

    return new Signed(canonicalRequest, stringToSign, signature, authorization, added);
  }

  /** Canonical headers: lower-case names, sorted, with repeated names merged and values normalised. */
  private static TreeMap<String, String> canonicalHeaders(Request request, List<Map.Entry<String, String>> added) {
    TreeMap<String, String> headers = new TreeMap<>();
    headers.put("host", normalizeValue(request.host));
    for (Map.Entry<String, String> header : request.headers) {
      String name = header.getKey().trim().toLowerCase(Locale.ROOT);
      if (MANAGED_HEADERS.contains(name)) {
        throw new IllegalArgumentException("Header '" + header.getKey() + "' is managed by the signer and must not be set");
      }
      headers.merge(name, normalizeValue(header.getValue()), (a, b) -> a + "," + b);
    }
    for (Map.Entry<String, String> header : added) {
      headers.put(header.getKey().toLowerCase(Locale.ROOT), normalizeValue(header.getValue()));
    }
    return headers;
  }

  /** Trims, and collapses every run of whitespace (including obsolete line folding) to one space. */
  private static String normalizeValue(String value) {
    StringBuilder out = new StringBuilder(value.length());
    boolean pendingSpace = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pendingSpace = out.length() > 0;
      } else {
        if (pendingSpace) {
          out.append(' ');
          pendingSpace = false;
        }
        out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code kSigning = HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), service), "aws4_request")}, cached. */
  private byte[] signingKey(String dateStamp, String secretKey) {
    SigningKey cached = signingKey;
    if (cached != null && cached.matches(dateStamp, secretKey)) {
      return cached.key;
    }
    byte[] kDate = HashUtil.hmacSha256((AWS4 + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
    byte[] kRegion = HashUtil.hmacSha256(kDate, region);
    byte[] kService = HashUtil.hmacSha256(kRegion, service);
    byte[] kSigning = HashUtil.hmacSha256(kService, AWS4_REQUEST);
    signingKey = new SigningKey(dateStamp, secretKey, kSigning); // replaces, never accumulates
    return kSigning;
  }

  // --- encoding helpers shared with the transport, so the wire request equals what is signed ---

  /**
   * S3 canonical URI and wire path: every segment encoded once, {@code /} kept, no normalisation.
   *
   * @param path the decoded path, starting with {@code /} (e.g. {@code /bucket/my key.txt})
   */
  static String encodePath(String path) {
    Objects.requireNonNull(path, "path");
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("Path must start with '/': " + path);
    }
    return uriEncode(path, false);
  }

  /**
   * Canonical query string, which is also used as the wire query string: names and values
   * RFC 3986-encoded (including {@code /}), sorted by name then value, joined with {@code &}. A
   * parameter without a value is rendered {@code name=}.
   */
  static String canonicalQuery(List<Map.Entry<String, String>> query) {
    if (query.isEmpty()) {
      return "";
    }
    List<String[]> encoded = new ArrayList<>(query.size());
    for (Map.Entry<String, String> param : query) {
      encoded.add(new String[] { uriEncode(param.getKey(), true), uriEncode(param.getValue(), true) });
    }
    encoded.sort((a, b) -> {
      int byName = a[0].compareTo(b[0]);
      return byName != 0 ? byName : a[1].compareTo(b[1]);
    });
    StringBuilder out = new StringBuilder();
    for (String[] param : encoded) {
      if (out.length() > 0) {
        out.append('&');
      }
      out.append(param[0]).append('=').append(param[1]);
    }
    return out.toString();
  }

  /**
   * Host header value: the host, plus {@code :port} when the port is not the scheme default.
   * IPv6 literals are bracketed.
   *
   * @param port the port, or {@code -1} for the scheme default
   */
  static String hostHeader(String host, int port, boolean tls) {
    requireNonBlank(host, "host");
    String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    int defaultPort = tls ? 443 : 80;
    return port <= 0 || port == defaultPort ? h : h + ":" + port;
  }

  /** RFC 3986 percent-encoding of the UTF-8 bytes; only unreserved characters stay literal. */
  static String uriEncode(String value, boolean encodeSlash) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder out = new StringBuilder(bytes.length + 16);
    for (byte b : bytes) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)))
          .append(Character.toUpperCase(Character.forDigit(c & 0x0f, 16)));
      }
    }
    return out.toString();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  // --- value types ----------------------------------------------------------------------------

  /** What to sign. Immutable; the lists are copied. */
  static final class Request {
    final String method;
    final String host;
    final String path;
    final List<Map.Entry<String, String>> query;
    final List<Map.Entry<String, String>> headers;
    final String payloadHash;

    /**
     * @param host the Host header value, see {@link #hostHeader}
     * @param path the decoded path, starting with {@code /}
     * @param query decoded query parameters (an empty value means {@code name=})
     * @param headers additional headers to sign, excluding signer-managed ones
     * @param payloadHash hex SHA-256 of the body, or {@link #UNSIGNED_PAYLOAD}
     */
    Request(String method, String host, String path, List<Map.Entry<String, String>> query,
            List<Map.Entry<String, String>> headers, String payloadHash) {
      this.method = requireNonBlank(method, "method");
      this.host = requireNonBlank(host, "host");
      this.path = Objects.requireNonNull(path, "path");
      this.query = List.copyOf(query);
      this.headers = List.copyOf(headers);
      this.payloadHash = requireNonBlank(payloadHash, "payloadHash");
    }
  }

  /** Result of {@link #sign}: intermediate values (for tests and diagnostics) and the headers to add. */
  static final class Signed {
    final String canonicalRequest;
    final String stringToSign;
    final String signature;
    final String authorization;
    /** Headers the caller must add, in order: X-Amz-Date, [X-Amz-Content-Sha256], [X-Amz-Security-Token], Authorization. */
    final List<Map.Entry<String, String>> headers;

    private Signed(String canonicalRequest, String stringToSign, String signature, String authorization,
                   List<Map.Entry<String, String>> headers) {
      this.canonicalRequest = canonicalRequest;
      this.stringToSign = stringToSign;
      this.signature = signature;
      this.authorization = authorization;
      this.headers = Collections.unmodifiableList(headers);
    }
  }

  /** The single cached signing key. */
  private static final class SigningKey {
    final String dateStamp;
    final String secretKey;
    final byte[] key;

    SigningKey(String dateStamp, String secretKey, byte[] key) {
      this.dateStamp = dateStamp;
      this.secretKey = secretKey;
      this.key = key;
    }

    boolean matches(String dateStamp, String secretKey) {
      return this.dateStamp.equals(dateStamp) && this.secretKey.equals(secretKey);
    }
  }
}
