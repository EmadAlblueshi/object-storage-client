package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Headers signed into a presigned upload-part URL, for
 * {@link S3Presigner#presignUploadPart(String, String, String, int, long, java.util.concurrent.TimeUnit, S3PresignUploadPartOptions)}.
 * The uploader must send each one exactly as given, otherwise the server rejects the part; the
 * {@linkplain S3PresignedRequest#getHeaders() headers} of the result list them. A part has no
 * content type of its own: the object's is set by {@link S3Client#createMultipartUpload}. Invalid
 * values make presigning throw {@link IllegalArgumentException}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3PresignUploadPartOptions {

  private String contentMd5;

  public S3PresignUploadPartOptions() {
  }

  public S3PresignUploadPartOptions(S3PresignUploadPartOptions other) {
    this.contentMd5 = other.contentMd5;
  }

  public S3PresignUploadPartOptions(JsonObject json) {
    S3PresignUploadPartOptionsConverter.fromJson(json, this);
  }

  /** The {@code Content-MD5} the part upload must send; {@code null} for none. */
  public String getContentMd5() {
    return contentMd5;
  }

  /**
   * Sets the {@code Content-MD5} the part upload must send: the base64 encoding (with padding) of
   * the 16-byte MD5 digest of the part. S3 also checks the part against it, so the URL accepts only
   * those exact bytes.
   */
  public S3PresignUploadPartOptions setContentMd5(String contentMd5) {
    this.contentMd5 = contentMd5;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PresignUploadPartOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3PresignUploadPartOptions{contentMd5=" + contentMd5 + '}';
  }
}
