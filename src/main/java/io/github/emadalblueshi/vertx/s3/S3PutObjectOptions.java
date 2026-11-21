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
  private Map<String, String> userMetadata = new LinkedHashMap<>();

  public S3PutObjectOptions() {
  }

  public S3PutObjectOptions(S3PutObjectOptions other) {
    this.contentType = other.contentType;
    this.userMetadata = new LinkedHashMap<>(other.userMetadata);
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
   * User metadata, sent as {@code x-amz-meta-<name>} headers. Names are HTTP token characters and
   * case-insensitive (S3 stores them lowercase); values are printable US-ASCII. Non-ASCII values
   * are rejected, not encoded: if you need them (e.g. Arabic file names), URL-encode them yourself,
   * or keep such data in the object key or in your own database. Invalid entries, including two
   * names that differ only in case, fail {@code putObject} before any request is sent.
   * Unmodifiable.
   */
  public Map<String, String> getUserMetadata() {
    return Collections.unmodifiableMap(userMetadata);
  }

  /** Replaces all user metadata with a copy of {@code userMetadata}. */
  public S3PutObjectOptions setUserMetadata(Map<String, String> userMetadata) {
    this.userMetadata = new LinkedHashMap<>(Objects.requireNonNull(userMetadata, "userMetadata"));
    return this;
  }

  /**
   * Adds one user metadata entry, replacing any entry whose name differs only in case. The same
   * rules as for {@link #getUserMetadata()} apply, checked by {@code putObject}.
   *
   * @throws NullPointerException if {@code name} or {@code value} is {@code null}
   */
  public S3PutObjectOptions putUserMetadata(String name, String value) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
    userMetadata.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
    userMetadata.put(name, value);
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PutObjectOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3PutObjectOptions{contentType=" + contentType + ", userMetadata=" + userMetadata + '}';
  }
}
