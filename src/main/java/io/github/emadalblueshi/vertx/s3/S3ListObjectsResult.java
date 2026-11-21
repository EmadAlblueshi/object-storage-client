package io.github.emadalblueshi.vertx.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/** One page of a {@link S3Client#listObjects} listing. */
@DataObject
@JsonGen(publicConverter = false)
public class S3ListObjectsResult {

  private List<S3ListedObject> objects = new ArrayList<>();
  private List<String> commonPrefixes = new ArrayList<>();
  private boolean truncated;
  private String nextContinuationToken;

  public S3ListObjectsResult() {
  }

  /** Deep copy. */
  public S3ListObjectsResult(S3ListObjectsResult other) {
    for (S3ListedObject object : other.objects) {
      objects.add(new S3ListedObject(object));
    }
    commonPrefixes.addAll(other.commonPrefixes);
    this.truncated = other.truncated;
    this.nextContinuationToken = other.nextContinuationToken;
  }

  public S3ListObjectsResult(JsonObject json) {
    S3ListObjectsResultConverter.fromJson(json, this);
  }

  /** The objects on this page, in key order. Never {@code null}. */
  public List<S3ListedObject> getObjects() {
    return objects;
  }

  public S3ListObjectsResult setObjects(List<S3ListedObject> objects) {
    this.objects = Objects.requireNonNull(objects, "objects");
    return this;
  }

  /**
   * With a delimiter: the distinct key prefixes up to and including the delimiter after the
   * requested prefix ("folders"), decoded. Never {@code null}.
   */
  public List<String> getCommonPrefixes() {
    return commonPrefixes;
  }

  public S3ListObjectsResult setCommonPrefixes(List<String> commonPrefixes) {
    this.commonPrefixes = Objects.requireNonNull(commonPrefixes, "commonPrefixes");
    return this;
  }

  /** Whether more results follow; then {@link #getNextContinuationToken()} fetches the next page. */
  public boolean isTruncated() {
    return truncated;
  }

  public S3ListObjectsResult setTruncated(boolean truncated) {
    this.truncated = truncated;
    return this;
  }

  /** The token for the next page, or {@code null} on the last page. */
  public String getNextContinuationToken() {
    return nextContinuationToken;
  }

  public S3ListObjectsResult setNextContinuationToken(String nextContinuationToken) {
    this.nextContinuationToken = nextContinuationToken;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ListObjectsResultConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ListObjectsResult{objects=" + objects + ", commonPrefixes=" + commonPrefixes + ", truncated=" + truncated
      + ", nextContinuationToken=" + nextContinuationToken + '}';
  }
}
