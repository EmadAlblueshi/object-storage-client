package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Headers signed into a presigned {@code PUT} URL, for
 * {@link S3Presigner#presignPut(String, String, long, java.util.concurrent.TimeUnit, S3PresignPutOptions)}.
 * The uploader must send each one exactly as given, otherwise the server rejects the upload; the
 * {@linkplain S3PresignedRequest#getHeaders() headers} of the result list them. Invalid values
 * make presigning throw {@link IllegalArgumentException}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3PresignPutOptions {

  private String contentType;
  private String contentMd5;
  private Long contentLength;

  public S3PresignPutOptions() {
  }

  public S3PresignPutOptions(S3PresignPutOptions other) {
    this.contentType = other.contentType;
    this.contentMd5 = other.contentMd5;
    this.contentLength = other.contentLength;
  }

  public S3PresignPutOptions(JsonObject json) {
    S3PresignPutOptionsConverter.fromJson(json, this);
  }

  /** The {@code Content-Type} the upload must send, which S3 stores with the object; {@code null} for none. */
  public String getContentType() {
    return contentType;
  }

  /**
   * Sets the {@code Content-Type} the upload must send: non-empty printable US-ASCII. It is
   * compared byte for byte, so the uploader must not add parameters such as {@code ; charset=...}.
   */
  public S3PresignPutOptions setContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /** The {@code Content-MD5} the upload must send; {@code null} for none. */
  public String getContentMd5() {
    return contentMd5;
  }

  /**
   * Sets the {@code Content-MD5} the upload must send: the base64 encoding (with padding) of the
   * 16-byte MD5 digest of the body. S3 also checks the body against it, so the URL accepts only
   * those exact bytes.
   */
  public S3PresignPutOptions setContentMd5(String contentMd5) {
    this.contentMd5 = contentMd5;
    return this;
  }

  /** The {@code Content-Length} the upload must send; {@code null} for none. */
  public @Nullable Long getContentLength() {
    return contentLength;
  }

  /**
   * Sets the {@code Content-Length} the upload must send, which pins the exact size of the object:
   * 0 to 5 GiB (S3's limit for a single {@code PUT}; upload larger files as a multipart upload).
   * The uploader must send a real {@code Content-Length} header, not a chunked body.
   *
   * @param contentLength the size in bytes, or {@code null} for none
   */
  public S3PresignPutOptions setContentLength(@Nullable Long contentLength) {
    this.contentLength = contentLength;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PresignPutOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3PresignPutOptions{contentType=" + contentType + ", contentMd5=" + contentMd5 + ", contentLength="
      + contentLength + '}';
  }
}
