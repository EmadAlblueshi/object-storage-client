package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the AWS Signature Version 4 test suite (vendored from botocore under
 * {@code src/test/resources/aws4-testsuite}, Apache-2.0, see the LICENSE and NOTICE there).
 * <p>
 * For each case, the raw request ({@code .req}) is parsed and signed. The canonical request,
 * the string to sign and the Authorization header must match the expected files byte for byte.
 * The suite targets a generic service ({@code service}, no {@code X-Amz-Content-Sha256}). The
 * path-normalisation cases don't apply to S3 and are absent from this suite version; S3
 * specifics are covered by {@code S3SignatureV4Test} and against Silo.
 */
class S3SignatureV4TestSuiteTest {

  private static final String ACCESS_KEY = "AKIDEXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
  private static final String REGION = "us-east-1";
  private static final String SERVICE = "service";
  private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
    "get-header-key-duplicate", "get-header-value-multiline", "get-header-value-order", "get-header-value-trim",
    "get-unreserved", "get-utf8", "get-vanilla", "get-vanilla-empty-query-key", "get-vanilla-query",
    "get-vanilla-query-order-encoded", "get-vanilla-query-order-key", "get-vanilla-query-order-key-case",
    "get-vanilla-query-order-value", "get-vanilla-query-unreserved", "get-vanilla-utf8-query",
    "get-vanilla-with-session-token", "post-header-key-case", "post-header-key-sort", "post-header-value-case",
    "post-vanilla", "post-vanilla-empty-query-value", "post-vanilla-query", "post-x-www-form-urlencoded",
    "post-x-www-form-urlencoded-parameters"
  })
  void matchesAwsTestSuite(String name) throws IOException {
    String expectedCanonical = resource(name, "creq");
    ParsedRequest parsed = ParsedRequest.parse(resource(name, "req"));
    String sessionToken = sessionTokenFrom(expectedCanonical);

    Clock clock = Clock.fixed(ZonedDateTime.parse(parsed.amzDate, AMZ_DATE.withZone(ZoneOffset.UTC)).toInstant(), ZoneOffset.UTC);
    S3SignatureV4 signer = new S3SignatureV4(REGION, SERVICE, clock, false);
    S3SignatureV4.Request request = new S3SignatureV4.Request(parsed.method, parsed.host, parsed.path, parsed.query,
      parsed.headers, HashUtil.sha256Hex(parsed.body.getBytes(StandardCharsets.UTF_8)));

    S3SignatureV4.Signed signed = signer.sign(request, ACCESS_KEY, SECRET_KEY, sessionToken);

    assertEquals(expectedCanonical, signed.canonicalRequest, "canonical request");
    assertEquals(resource(name, "sts"), signed.stringToSign, "string to sign");
    assertEquals(resource(name, "authz"), signed.authorization, "Authorization header");
  }

  /** The suite passes the session token out-of-band; it only appears in the expected canonical request. */
  private static String sessionTokenFrom(String canonicalRequest) {
    for (String line : canonicalRequest.split("\n")) {
      if (line.startsWith("x-amz-security-token:")) {
        return line.substring("x-amz-security-token:".length());
      }
    }
    return null;
  }

  private static String resource(String name, String extension) throws IOException {
    String path = "/aws4-testsuite/" + name + "/" + name + "." + extension;
    try (InputStream in = S3SignatureV4TestSuiteTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "missing test resource " + path);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      in.transferTo(out);
      String text = out.toString(StandardCharsets.UTF_8);
      return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }
  }

  /** Minimal parser for the suite's raw HTTP request files. */
  private static final class ParsedRequest {
    String method;
    String host;
    String amzDate;
    String path;
    final List<Map.Entry<String, String>> query = new ArrayList<>();
    final List<Map.Entry<String, String>> headers = new ArrayList<>();
    String body = "";

    static ParsedRequest parse(String raw) {
      ParsedRequest r = new ParsedRequest();
      String[] lines = raw.split("\n", -1);
      String requestLine = lines[0];
      r.method = requestLine.substring(0, requestLine.indexOf(' '));
      String target = requestLine.substring(requestLine.indexOf(' ') + 1, requestLine.lastIndexOf(" HTTP/1.1"));
      int q = target.indexOf('?');
      r.path = percentDecode(q < 0 ? target : target.substring(0, q));
      if (q >= 0) {
        for (String param : target.substring(q + 1).split("&")) {
          int eq = param.indexOf('=');
          String key = eq < 0 ? param : param.substring(0, eq);
          String value = eq < 0 ? "" : param.substring(eq + 1);
          r.query.add(Map.entry(percentDecode(key), percentDecode(value)));
        }
      }
      List<String[]> rawHeaders = new ArrayList<>();
      int i = 1;
      for (; i < lines.length && !lines[i].isEmpty(); i++) {
        String line = lines[i];
        if (Character.isWhitespace(line.charAt(0)) && !rawHeaders.isEmpty()) {
          String[] last = rawHeaders.get(rawHeaders.size() - 1);
          last[1] = last[1] + "\n" + line; // obsolete line folding
        } else {
          int colon = line.indexOf(':');
          rawHeaders.add(new String[] { line.substring(0, colon), line.substring(colon + 1) });
        }
      }
      for (String[] header : rawHeaders) {
        if (header[0].equalsIgnoreCase("Host")) {
          r.host = header[1].trim();
        } else if (header[0].equalsIgnoreCase("X-Amz-Date")) {
          r.amzDate = header[1].trim();
        } else {
          r.headers.add(Map.entry(header[0], header[1]));
        }
      }
      if (i < lines.length - 1) {
        r.body = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
      }
      return r;
    }

    private static String percentDecode(String s) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
      for (int i = 0; i < bytes.length; i++) {
        if (bytes[i] == '%' && i + 2 < bytes.length) {
          out.write(Integer.parseInt(new String(bytes, i + 1, 2, StandardCharsets.US_ASCII), 16));
          i += 2;
        } else {
          out.write(bytes[i]);
        }
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
