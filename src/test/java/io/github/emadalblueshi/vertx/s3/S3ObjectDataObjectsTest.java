package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

/** The step 6 data objects: copy semantics, JSON round trip, never-null metadata. */
class S3ObjectDataObjectsTest {

  @Test
  void objectMetadataDefaultsCopyAndJson() {
    S3ObjectMetadata empty = new S3ObjectMetadata();
    assertEquals(-1, empty.getContentLength());
    assertEquals(Map.of(), empty.getUserMetadata());

    S3ObjectMetadata metadata = new S3ObjectMetadata().setContentLength(10).setContentType("text/plain").setEtag("\"e\"")
      .setLastModified(Instant.parse("2026-11-20T10:15:30Z")).setUserMetadata(Map.of("k", "v"));
    JsonObject json = metadata.toJson();
    assertEquals(new JsonObject().put("contentLength", 10L).put("contentType", "text/plain").put("etag", "\"e\"")
      .put("lastModified", "2026-11-20T10:15:30Z").put("userMetadata", new JsonObject().put("k", "v")), json);
    assertEquals(json, new S3ObjectMetadata(json).toJson());
    assertEquals(json, new S3ObjectMetadata(metadata).toJson());
    assertThrows(UnsupportedOperationException.class, () -> metadata.getUserMetadata().put("x", "y"));
  }

  @Test
  void putObjectOptionsCopyAndJson() {
    Map<String, String> source = new HashMap<>(Map.of("k", "v"));
    S3PutObjectOptions options = new S3PutObjectOptions().setContentType("image/png").setUserMetadata(source);
    source.put("later", "ignored");
    assertEquals(Map.of("k", "v"), options.getUserMetadata(), "the map is copied");

    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("contentType", "image/png").put("userMetadata", new JsonObject().put("k", "v")), json);
    assertEquals(json, new S3PutObjectOptions(json).toJson());
    assertEquals(json, new S3PutObjectOptions(options).toJson());
    assertNull(new S3PutObjectOptions().getContentType());
    assertEquals(Map.of(), new S3PutObjectOptions().getUserMetadata());
    assertThrows(UnsupportedOperationException.class, () -> options.getUserMetadata().put("x", "y"));
  }

  @Test
  void putUserMetadataAddsOrReplacesCaseInsensitively() {
    S3PutObjectOptions options = new S3PutObjectOptions()
      .putUserMetadata("Camera", "front")
      .putUserMetadata("owner-id", "42")
      .putUserMetadata("camera", "back");
    assertEquals(Map.of("owner-id", "42", "camera", "back"), options.getUserMetadata());
    assertEquals(java.util.List.of("owner-id", "camera"), new java.util.ArrayList<>(options.getUserMetadata().keySet()),
      "a replaced entry takes the new name and goes last");
    assertThrows(NullPointerException.class, () -> options.putUserMetadata(null, "v"));
    assertThrows(NullPointerException.class, () -> options.putUserMetadata("n", null));
    // putUserMetadata is not a JSON property of its own: only the map is written and read
    JsonObject json = options.toJson();
    assertEquals(java.util.Set.of("userMetadata"), json.fieldNames());
    assertEquals(options.getUserMetadata(), new S3PutObjectOptions(json).getUserMetadata());
  }

  @Test
  void listObjectsOptionsCopyJsonAndValidation() {
    S3ListObjectsOptions options = new S3ListObjectsOptions().setPrefix("p/").setDelimiter("/").setMaxKeys(10)
      .setContinuationToken("token").setStartAfter("p/a");
    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("prefix", "p/").put("delimiter", "/").put("maxKeys", 10)
      .put("continuationToken", "token").put("startAfter", "p/a"), json);
    assertEquals(json, new S3ListObjectsOptions(json).toJson());
    assertEquals(json, new S3ListObjectsOptions(options).toJson());
    assertNull(new S3ListObjectsOptions().getMaxKeys());
    assertThrows(IllegalArgumentException.class, () -> new S3ListObjectsOptions().setMaxKeys(0));
    assertThrows(IllegalArgumentException.class, () -> new S3ListObjectsOptions().setMaxKeys(1001));
    new S3ListObjectsOptions().setMaxKeys(1000).setMaxKeys(null);
  }

  @Test
  void listObjectsResultDeepCopyAndJson() {
    S3ListObjectsResult result = new S3ListObjectsResult()
      .setObjects(new java.util.ArrayList<>(java.util.List.of(new S3ListedObject().setKey("k").setSize(3).setEtag("\"e\"")
        .setLastModified(Instant.parse("2026-11-20T10:15:30Z")))))
      .setCommonPrefixes(new java.util.ArrayList<>(java.util.List.of("dir/")))
      .setTruncated(true)
      .setNextContinuationToken("next");
    JsonObject json = result.toJson();
    assertEquals(json, new S3ListObjectsResult(json).toJson());
    S3ListObjectsResult copy = new S3ListObjectsResult(result);
    result.getObjects().get(0).setKey("changed");
    result.getCommonPrefixes().add("other/");
    assertEquals("k", copy.getObjects().get(0).getKey());
    assertEquals(java.util.List.of("dir/"), copy.getCommonPrefixes());
    assertTrue(new S3ListObjectsResult().getObjects().isEmpty());
  }
}
