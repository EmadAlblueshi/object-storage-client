package io.github.emadalblueshi.vertx.s3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Optional settings for {@link S3Client#putObject(String, String, io.vertx.core.buffer.Buffer, S3PutObjectOptions)}.
 * Invalid values fail the operation with an {@link S3ClientException} before any request is sent.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3PutObjectOptions {

  private String contentType;
  private Map<String, String> metadata = new LinkedHashMap<>();

  public S3PutObjectOptions() {
  }

  public S3PutObjectOptions(S3PutObjectOptions other) {
    this.contentType = other.contentType;
    this.metadata = new LinkedHashMap<>(other.metadata);
  }

  public S3PutObjectOptions(JsonObject json) {
    S3PutObjectOptionsConverter.fromJson(json, this);
  }

  /** The {@code Content-Type} stored with the object; {@code null} lets the server choose (usually {@code binary/octet-stream}). */
  public String getContentType() {
    return contentType;
  }

  public S3PutObjectOptions setContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /**
   * User metadata, sent as {@code x-amz-meta-<name>} headers. Names are HTTP token characters
   * (stored lowercase by S3); values are printable US-ASCII. Non-ASCII values are rejected, not
   * encoded: if you need them (e.g. Arabic file names), URL-encode them yourself, or keep such
   * data in the object key or in your own database.
   */
  public Map<String, String> getMetadata() {
    return Collections.unmodifiableMap(metadata);
  }

  public S3PutObjectOptions setMetadata(Map<String, String> metadata) {
    this.metadata = new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata"));
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PutObjectOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3PutObjectOptions{contentType=" + contentType + ", metadata=" + metadata + '}';
  }
}
