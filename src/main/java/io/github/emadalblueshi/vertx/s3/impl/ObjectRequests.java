package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;

/**
 * Object-request checks and header mapping, all done before any I/O or from response headers.
 * Check methods return a problem description, or {@code null} when the input is valid.
 */
final class ObjectRequests {

  static final String META_PREFIX = "x-amz-meta-";

  private static final int MAX_KEY_BYTES = 1024;
  private static final Pattern RANGE = Pattern.compile("bytes=(?:(\\d{1,18})-(\\d{0,18})|-(\\d{1,18}))");
  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
  private static final Pattern HEADER_VALUE = Pattern.compile("[\\x20-\\x7E]*");

  private ObjectRequests() {
  }

  /** S3 keys: 1 to 1024 bytes of valid UTF-8. Everything else (e.g. {@code /}, spaces, unicode) is encoded on the wire. */
  static String keyProblem(String key) {
    if (key == null) {
      return "The object key is required";
    }
    if (key.isEmpty()) {
      return "The object key must not be empty";
    }
    int utf8 = utf8Length(key);
    if (utf8 < 0) {
      return "The object key is not valid Unicode (unpaired surrogate)";
    }
    if (utf8 > MAX_KEY_BYTES) {
      return "The object key is longer than " + MAX_KEY_BYTES + " bytes in UTF-8";
    }
    return null;
  }

  /** Checks the ListObjectsV2 options ({@code null} means none): text values must be valid Unicode. */
  static String listProblem(S3ListObjectsOptions options) {
    if (options == null) {
      return null;
    }
    String[][] values = {{"prefix", options.getPrefix()}, {"delimiter", options.getDelimiter()},
      {"startAfter", options.getStartAfter()}, {"continuationToken", options.getContinuationToken()}};
    for (String[] value : values) {
      if (value[1] != null && utf8Length(value[1]) < 0) {
        return "The " + value[0] + " is not valid Unicode (unpaired surrogate)";
      }
    }
    return null;
  }

  /**
   * The ListObjectsV2 query: always {@code list-type=2} and {@code encoding-type=url} (so keys
   * of any characters survive the XML response), plus the options that are set and non-empty.
   */
  static List<Map.Entry<String, String>> listQuery(S3ListObjectsOptions options) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    query.add(Map.entry("list-type", "2"));
    query.add(Map.entry("encoding-type", "url"));
    if (options != null) {
      addIfSet(query, "prefix", options.getPrefix());
      addIfSet(query, "delimiter", options.getDelimiter());
      addIfSet(query, "max-keys", options.getMaxKeys() == null ? null : options.getMaxKeys().toString());
      addIfSet(query, "continuation-token", options.getContinuationToken());
      addIfSet(query, "start-after", options.getStartAfter());
    }
    return query;
  }

  private static void addIfSet(List<Map.Entry<String, String>> query, String name, String value) {
    if (value != null && !value.isEmpty()) {
      query.add(Map.entry(name, value));
    }
  }

  /** The UTF-8 length of {@code value}, or {@code -1} if it isn't valid Unicode. */
  private static int utf8Length(String value) {
    try {
      return StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
        .remaining();
    } catch (CharacterCodingException e) {
      return -1;
    }
  }

  /** A single HTTP byte range: {@code bytes=first-last}, {@code bytes=first-} or {@code bytes=-suffixLength}. */
  static String rangeProblem(String range) {
    if (range == null) {
      return "The range is required";
    }
    Matcher m = RANGE.matcher(range);
    if (!m.matches()) {
      return "Invalid range \"" + range + "\": expected bytes=first-last, bytes=first- or bytes=-suffixLength";
    }
    if (m.group(2) != null && !m.group(2).isEmpty() && Long.parseLong(m.group(1)) > Long.parseLong(m.group(2))) {
      return "Invalid range \"" + range + "\": the first byte is after the last";
    }
    if (m.group(3) != null && Long.parseLong(m.group(3)) == 0) {
      return "Invalid range \"" + range + "\": the suffix length must be positive";
    }
    return null;
  }

  /** Whether {@code value} can be sent as a header value by this client: non-empty printable US-ASCII. */
  static boolean isHeaderValue(String value) {
    return value != null && !value.isEmpty() && HEADER_VALUE.matcher(value).matches();
  }

  /** The PutObject headers for {@code options} ({@code null} means none); throws {@link IllegalArgumentException} with the problem. */
  static List<Map.Entry<String, String>> putHeaders(S3PutObjectOptions options) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    if (options == null) {
      return headers;
    }
    if (options.getContentType() != null) {
      if (!isHeaderValue(options.getContentType())) {
        throw new IllegalArgumentException("The content type must be non-empty printable US-ASCII");
      }
      headers.add(Map.entry(HttpHeaders.CONTENT_TYPE.toString(), options.getContentType()));
    }
    Map<String, String> seen = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : options.getUserMetadata().entrySet()) {
      String name = entry.getKey();
      if (name == null || !TOKEN.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid metadata name \"" + name + "\": use HTTP token characters "
          + "(letters, digits and !#$%&'*+.^_`|~-)");
      }
      String lower = name.toLowerCase(Locale.ROOT);
      if (seen.put(lower, name) != null) {
        throw new IllegalArgumentException("Duplicate metadata name \"" + lower + "\" (names are case-insensitive)");
      }
      String value = entry.getValue();
      if (value == null || !HEADER_VALUE.matcher(value).matches()) {
        throw new IllegalArgumentException("Invalid value for metadata \"" + name + "\": use printable US-ASCII");
      }
      headers.add(Map.entry(META_PREFIX + lower, value));
    }
    return headers;
  }

  /** The object metadata reported by a HEAD or GET response. */
  static S3ObjectMetadata metadata(MultiMap headers) {
    Map<String, String> user = new LinkedHashMap<>();
    for (String name : headers.names()) {
      if (name.regionMatches(true, 0, META_PREFIX, 0, META_PREFIX.length()) && name.length() > META_PREFIX.length()) {
        user.put(name.substring(META_PREFIX.length()).toLowerCase(Locale.ROOT), String.join(",", headers.getAll(name)));
      }
    }
    return new S3ObjectMetadata()
      .setContentLength(parseLong(headers.get(HttpHeaders.CONTENT_LENGTH)))
      .setContentType(headers.get(HttpHeaders.CONTENT_TYPE))
      .setEtag(headers.get(HttpHeaders.ETAG))
      .setLastModified(parseHttpDate(headers.get(HttpHeaders.LAST_MODIFIED)))
      .setUserMetadata(user);
  }

  private static long parseLong(String value) {
    if (value == null) {
      return -1;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static Instant parseHttpDate(String value) {
    if (value == null) {
      return null;
    }
    try {
      return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
