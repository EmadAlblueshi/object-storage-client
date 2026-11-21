package io.github.emadalblueshi.vertx.s3;

import java.time.Instant;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * A bucket as listed by {@link S3Client#listBuckets()}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3Bucket {

  private String name;
  private Instant creationDate;

  public S3Bucket() {
  }

  public S3Bucket(S3Bucket other) {
    this.name = other.name;
    this.creationDate = other.creationDate;
  }

  public S3Bucket(JsonObject json) {
    S3BucketConverter.fromJson(json, this);
  }

  /** The bucket name. */
  public String getName() {
    return name;
  }

  public S3Bucket setName(String name) {
    this.name = name;
    return this;
  }

  /** When the bucket was created, or {@code null} if the server didn't report it. */
  public Instant getCreationDate() {
    return creationDate;
  }

  public S3Bucket setCreationDate(Instant creationDate) {
    this.creationDate = creationDate;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3BucketConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3Bucket{name=" + name + ", creationDate=" + creationDate + '}';
  }
}
