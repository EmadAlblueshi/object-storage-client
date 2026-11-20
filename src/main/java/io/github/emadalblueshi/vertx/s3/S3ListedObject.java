package io.github.emadalblueshi.vertx.s3;

import java.time.Instant;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/** An object as listed by {@link S3Client#listObjects}. */
@DataObject
@JsonGen(publicConverter = false)
public class S3ListedObject {

  private String key;
  private long size;
  private String etag;
  private Instant lastModified;

  public S3ListedObject() {
  }

  public S3ListedObject(S3ListedObject other) {
    this.key = other.key;
    this.size = other.size;
    this.etag = other.etag;
    this.lastModified = other.lastModified;
  }

  public S3ListedObject(JsonObject json) {
    S3ListedObjectConverter.fromJson(json, this);
  }

  /** The object key, decoded. */
  public String getKey() {
    return key;
  }

  public S3ListedObject setKey(String key) {
    this.key = key;
    return this;
  }

  /** The size in bytes. */
  public long getSize() {
    return size;
  }

  public S3ListedObject setSize(long size) {
    this.size = size;
    return this;
  }

  /** The entity tag exactly as listed, including its double quotes; or {@code null}. */
  public String getEtag() {
    return etag;
  }

  public S3ListedObject setEtag(String etag) {
    this.etag = etag;
    return this;
  }

  /** When the object was last modified, or {@code null} if the server didn't report it. */
  public Instant getLastModified() {
    return lastModified;
  }

  public S3ListedObject setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ListedObjectConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ListedObject{key=" + key + ", size=" + size + ", etag=" + etag + ", lastModified=" + lastModified + '}';
  }
}
