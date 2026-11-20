package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.vertx.core.MultiMap;

class ObjectRequestsTest {

  // --- keys ---

  @ParameterizedTest
  @ValueSource(strings = {"a", "dir/sub/file.txt", "a b+c=d&e?f#g%h", "ünïcødé/文件", "..", "a//b", " lead"})
  void validKeys(String key) {
    assertNull(ObjectRequests.keyProblem(key));
  }

  @Test
  void keyLimitIs1024BytesOfUtf8() {
    assertNull(ObjectRequests.keyProblem("k".repeat(1024)));
    assertEquals("The object key is longer than 1024 bytes in UTF-8", ObjectRequests.keyProblem("k".repeat(1025)));
    assertNull(ObjectRequests.keyProblem("é".repeat(512)), "512 two-byte characters are 1024 bytes");
    assertEquals("The object key is longer than 1024 bytes in UTF-8", ObjectRequests.keyProblem("é".repeat(513)));
  }

  @Test
  void invalidKeys() {
    assertEquals("The object key is required", ObjectRequests.keyProblem(null));
    assertEquals("The object key must not be empty", ObjectRequests.keyProblem(""));
    assertEquals("The object key is not valid Unicode (unpaired surrogate)", ObjectRequests.keyProblem("a\uD800b"));
  }

  // --- ranges ---

  @ParameterizedTest
  @ValueSource(strings = {"bytes=0-0", "bytes=0-99", "bytes=100-", "bytes=-1", "bytes=5-5", "bytes=999999999999999999-"})
  void validRanges(String range) {
    assertNull(ObjectRequests.rangeProblem(range));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "0-99", "bytes=", "bytes=-", "bytes=a-b", "bytes=0-1,5-6", "items=0-1", " bytes=0-1",
    "bytes=0-1 ", "bytes=1234567890123456789-"})
  void malformedRanges(String range) {
    assertTrue(ObjectRequests.rangeProblem(range).startsWith("Invalid range \"" + range + "\": expected"),
      ObjectRequests.rangeProblem(range));
  }

  @Test
  void semanticallyInvalidRanges() {
    assertEquals("Invalid range \"bytes=5-2\": the first byte is after the last", ObjectRequests.rangeProblem("bytes=5-2"));
    assertEquals("Invalid range \"bytes=-0\": the suffix length must be positive", ObjectRequests.rangeProblem("bytes=-0"));
    assertEquals("The range is required", ObjectRequests.rangeProblem(null));
  }

  // --- PutObject headers ---

  @Test
  void noOptionsMeansNoHeaders() {
    assertEquals(List.of(), ObjectRequests.putHeaders(null));
    assertEquals(List.of(), ObjectRequests.putHeaders(new S3PutObjectOptions()));
  }

  @Test
  void contentTypeAndMetadataHeaders() {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("Owner-Id", "42");
    metadata.put("note", "a value, with spaces");
    List<Map.Entry<String, String>> headers = ObjectRequests.putHeaders(new S3PutObjectOptions()
      .setContentType("image/png").setMetadata(metadata));
    assertEquals(List.of(Map.entry("content-type", "image/png"), Map.entry("x-amz-meta-owner-id", "42"),
      Map.entry("x-amz-meta-note", "a value, with spaces")), headers);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "with space", "colon:", "café", "new\nline", "slash/"})
  void invalidMetadataNames(String name) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> ObjectRequests.putHeaders(new S3PutObjectOptions().setMetadata(Map.of(name, "v"))));
    assertTrue(e.getMessage().startsWith("Invalid metadata name"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"café", "line\r\nbreak", "tab\there", "nul\u0000"})
  void invalidMetadataValues(String value) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> ObjectRequests.putHeaders(new S3PutObjectOptions().setMetadata(Map.of("name", value))));
    assertEquals("Invalid value for metadata \"name\": use printable US-ASCII", e.getMessage());
  }

  @Test
  void metadataNamesAreCaseInsensitive() {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("Name", "a");
    metadata.put("NAME", "b");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> ObjectRequests.putHeaders(new S3PutObjectOptions().setMetadata(metadata)));
    assertEquals("Duplicate metadata name \"name\" (names are case-insensitive)", e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "text/plain\r\nX-Injected: 1", "téxt/plain"})
  void invalidContentTypes(String contentType) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> ObjectRequests.putHeaders(new S3PutObjectOptions().setContentType(contentType)));
    assertEquals("The content type must be non-empty printable US-ASCII", e.getMessage());
  }

  // --- response metadata ---

  @Test
  void metadataFromResponseHeaders() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap()
      .add("Content-Length", "1234")
      .add("Content-Type", "text/csv")
      .add("ETag", "\"0123abc\"")
      .add("Last-Modified", "Fri, 20 Nov 2026 10:15:30 GMT")
      .add("X-Amz-Meta-Owner-Id", "42")
      .add("x-amz-meta-note", "hello")
      .add("x-amz-request-id", "not-metadata");
    S3ObjectMetadata metadata = ObjectRequests.metadata(headers);
    assertEquals(1234, metadata.getContentLength());
    assertEquals("text/csv", metadata.getContentType());
    assertEquals("\"0123abc\"", metadata.getEtag());
    assertEquals(Instant.parse("2026-11-20T10:15:30Z"), metadata.getLastModified());
    assertEquals(Map.of("owner-id", "42", "note", "hello"), metadata.getMetadata());
  }

  @Test
  void missingOrInvalidHeadersAreLenient() {
    S3ObjectMetadata metadata = ObjectRequests.metadata(MultiMap.caseInsensitiveMultiMap()
      .add("Content-Length", "lots").add("Last-Modified", "yesterday"));
    assertEquals(-1, metadata.getContentLength());
    assertNull(metadata.getContentType());
    assertNull(metadata.getEtag());
    assertNull(metadata.getLastModified());
    assertEquals(Map.of(), metadata.getMetadata());
  }
}
