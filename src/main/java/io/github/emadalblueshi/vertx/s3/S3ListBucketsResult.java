package io.github.emadalblueshi.vertx.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Result of {@link S3Client#listBuckets()}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3ListBucketsResult {

  private List<S3Bucket> buckets = new ArrayList<>();
  private S3Owner owner;

  public S3ListBucketsResult() {
  }

  /** Deep copy. */
  public S3ListBucketsResult(S3ListBucketsResult other) {
    for (S3Bucket bucket : other.buckets) {
      buckets.add(new S3Bucket(bucket));
    }
    this.owner = other.owner == null ? null : new S3Owner(other.owner);
  }

  public S3ListBucketsResult(JsonObject json) {
    S3ListBucketsResultConverter.fromJson(json, this);
  }

  /** The buckets, in the order the server listed them. Never {@code null}. */
  public List<S3Bucket> getBuckets() {
    return buckets;
  }

  public S3ListBucketsResult setBuckets(List<S3Bucket> buckets) {
    this.buckets = Objects.requireNonNull(buckets, "buckets");
    return this;
  }

  /** The owner of the buckets, or {@code null} if the server didn't report it. */
  public S3Owner getOwner() {
    return owner;
  }

  public S3ListBucketsResult setOwner(S3Owner owner) {
    this.owner = owner;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ListBucketsResultConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ListBucketsResult{buckets=" + buckets + ", owner=" + owner + '}';
  }
}
