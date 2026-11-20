package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertEquals(Map.of(), empty.getMetadata());

    S3ObjectMetadata metadata = new S3ObjectMetadata().setContentLength(10).setContentType("text/plain").setEtag("\"e\"")
      .setLastModified(Instant.parse("2026-11-20T10:15:30Z")).setMetadata(Map.of("k", "v"));
    JsonObject json = metadata.toJson();
    assertEquals(new JsonObject().put("contentLength", 10L).put("contentType", "text/plain").put("etag", "\"e\"")
      .put("lastModified", "2026-11-20T10:15:30Z").put("metadata", new JsonObject().put("k", "v")), json);
    assertEquals(json, new S3ObjectMetadata(json).toJson());
    assertEquals(json, new S3ObjectMetadata(metadata).toJson());
    assertThrows(UnsupportedOperationException.class, () -> metadata.getMetadata().put("x", "y"));
  }

  @Test
  void putObjectOptionsCopyAndJson() {
    Map<String, String> source = new HashMap<>(Map.of("k", "v"));
    S3PutObjectOptions options = new S3PutObjectOptions().setContentType("image/png").setMetadata(source);
    source.put("later", "ignored");
    assertEquals(Map.of("k", "v"), options.getMetadata(), "the map is copied");

    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("contentType", "image/png").put("metadata", new JsonObject().put("k", "v")), json);
    assertEquals(json, new S3PutObjectOptions(json).toJson());
    assertEquals(json, new S3PutObjectOptions(options).toJson());
    assertNull(new S3PutObjectOptions().getContentType());
    assertEquals(Map.of(), new S3PutObjectOptions().getMetadata());
    assertThrows(UnsupportedOperationException.class, () -> options.getMetadata().put("x", "y"));
  }
}
