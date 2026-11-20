package io.github.emadalblueshi.vertx.s3;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * What S3 reports about an object, from the response headers of {@link S3Client#headObject} and
 * {@link S3Client#getObjectStream}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3ObjectMetadata {

  private long contentLength = -1;
  private String contentType;
  private String etag;
  private Instant lastModified;
  private Map<String, String> metadata = Collections.emptyMap();

  public S3ObjectMetadata() {
  }

  public S3ObjectMetadata(S3ObjectMetadata other) {
    this.contentLength = other.contentLength;
    this.contentType = other.contentType;
    this.etag = other.etag;
    this.lastModified = other.lastModified;
    setMetadata(other.metadata);
  }

  public S3ObjectMetadata(JsonObject json) {
    S3ObjectMetadataConverter.fromJson(json, this);
  }

  /**
   * The size in bytes: of the object, or of the requested range for a ranged
   * {@link S3Client#getObjectStream}. {@code -1} if the server didn't report it.
   */
  public long getContentLength() {
    return contentLength;
  }

  public S3ObjectMetadata setContentLength(long contentLength) {
    this.contentLength = contentLength;
    return this;
  }

  /** The {@code Content-Type}, or {@code null}. */
  public String getContentType() {
    return contentType;
  }

  public S3ObjectMetadata setContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /** The entity tag exactly as sent by the server, including its double quotes; or {@code null}. */
  public String getEtag() {
    return etag;
  }

  public S3ObjectMetadata setEtag(String etag) {
    this.etag = etag;
    return this;
  }

  /** When the object was last modified, or {@code null} if absent or unparsable. */
  public Instant getLastModified() {
    return lastModified;
  }

  public S3ObjectMetadata setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
    return this;
  }

  /**
   * User metadata ({@code x-amz-meta-*} headers), keyed by lowercase name without the prefix.
   * Never {@code null}; unmodifiable.
   */
  public Map<String, String> getMetadata() {
    return metadata;
  }

  public S3ObjectMetadata setMetadata(Map<String, String> metadata) {
    this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata")));
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ObjectMetadataConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ObjectMetadata{contentLength=" + contentLength + ", contentType=" + contentType + ", etag=" + etag
      + ", lastModified=" + lastModified + ", metadata=" + metadata + '}';
  }
}
