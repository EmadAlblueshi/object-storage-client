package io.github.emadalblueshi.vertx.s3;

import java.time.Instant;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/** A multipart upload that is in progress, as listed by {@link S3Client#listMultipartUploads}. */
@DataObject
@JsonGen(publicConverter = false)
public class S3MultipartUpload {

  private String key;
  private String uploadId;
  private Instant initiated;

  public S3MultipartUpload() {
  }

  public S3MultipartUpload(S3MultipartUpload other) {
    this.key = other.key;
    this.uploadId = other.uploadId;
    this.initiated = other.initiated;
  }

  public S3MultipartUpload(JsonObject json) {
    S3MultipartUploadConverter.fromJson(json, this);
  }

  /** The object key, decoded. */
  public String getKey() {
    return key;
  }

  public S3MultipartUpload setKey(String key) {
    this.key = key;
    return this;
  }

  /** The upload id. */
  public String getUploadId() {
    return uploadId;
  }

  public S3MultipartUpload setUploadId(String uploadId) {
    this.uploadId = uploadId;
    return this;
  }

  /** When the upload was created, or {@code null} if the server didn't say. */
  public Instant getInitiated() {
    return initiated;
  }

  public S3MultipartUpload setInitiated(Instant initiated) {
    this.initiated = initiated;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3MultipartUploadConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3MultipartUpload{key=" + key + ", uploadId=" + uploadId + ", initiated=" + initiated + '}';
  }
}
