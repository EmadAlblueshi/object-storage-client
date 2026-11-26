package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

/** The multipart data objects: copy semantics and JSON round trip. */
class S3MultipartDataObjectsTest {

  @Test
  void completedPartCopyAndJson() {
    S3CompletedPart part = new S3CompletedPart(3, "\"e3\"");
    JsonObject json = part.toJson();
    assertEquals(new JsonObject().put("partNumber", 3).put("etag", "\"e3\""), json);
    assertEquals(json, new S3CompletedPart(json).toJson());
    assertEquals(json, new S3CompletedPart(part).toJson());
    assertEquals(json, new S3CompletedPart().setPartNumber(3).setEtag("\"e3\"").toJson());
  }

  @Test
  void uploadedPartCopyAndJson() {
    S3UploadedPart part = new S3UploadedPart().setPartNumber(1).setEtag("\"e1\"").setSize(5_242_880L)
      .setLastModified(Instant.parse("2026-11-22T10:15:30Z"));
    JsonObject json = part.toJson();
    assertEquals(new JsonObject().put("partNumber", 1).put("etag", "\"e1\"").put("size", 5_242_880L)
      .put("lastModified", "2026-11-22T10:15:30Z"), json);
    assertEquals(json, new S3UploadedPart(json).toJson());
    assertEquals(json, new S3UploadedPart(part).toJson());
  }

  @Test
  void multipartUploadCopyAndJson() {
    S3MultipartUpload upload = new S3MultipartUpload().setKey("videos/a.mp4").setUploadId("u1")
      .setInitiated(Instant.parse("2026-11-22T10:15:30Z"));
    JsonObject json = upload.toJson();
    assertEquals(new JsonObject().put("key", "videos/a.mp4").put("uploadId", "u1").put("initiated", "2026-11-22T10:15:30Z"), json);
    assertEquals(json, new S3MultipartUpload(json).toJson());
    assertEquals(json, new S3MultipartUpload(upload).toJson());
  }
}
