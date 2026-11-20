package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Optional settings for {@link S3Client#listObjects(String, S3ListObjectsOptions)}. Unset (or
 * empty) values are not sent.
 * <p>
 * To page through a listing, pass the previous result's
 * {@link S3ListObjectsResult#getNextContinuationToken() next continuation token} as
 * {@link #setContinuationToken continuationToken}, with the same prefix and delimiter, until the
 * result is no longer {@link S3ListObjectsResult#isTruncated() truncated}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3ListObjectsOptions {

  private String prefix;
  private String delimiter;
  private Integer maxKeys;
  private String continuationToken;
  private String startAfter;

  public S3ListObjectsOptions() {
  }

  public S3ListObjectsOptions(S3ListObjectsOptions other) {
    this.prefix = other.prefix;
    this.delimiter = other.delimiter;
    this.maxKeys = other.maxKeys;
    this.continuationToken = other.continuationToken;
    this.startAfter = other.startAfter;
  }

  public S3ListObjectsOptions(JsonObject json) {
    S3ListObjectsOptionsConverter.fromJson(json, this);
  }

  /** Only keys starting with this prefix are listed. */
  public String getPrefix() {
    return prefix;
  }

  public S3ListObjectsOptions setPrefix(String prefix) {
    this.prefix = prefix;
    return this;
  }

  /**
   * Groups keys into {@linkplain S3ListObjectsResult#getCommonPrefixes() common prefixes} at the
   * first occurrence of the delimiter after the prefix; usually {@code "/"} to list "folders".
   */
  public String getDelimiter() {
    return delimiter;
  }

  public S3ListObjectsOptions setDelimiter(String delimiter) {
    this.delimiter = delimiter;
    return this;
  }

  /** The maximum number of keys and common prefixes per page, 1 to 1000; {@code null} for the server default (1000). */
  public Integer getMaxKeys() {
    return maxKeys;
  }

  public S3ListObjectsOptions setMaxKeys(Integer maxKeys) {
    if (maxKeys != null && (maxKeys < 1 || maxKeys > 1000)) {
      throw new IllegalArgumentException("maxKeys must be between 1 and 1000");
    }
    this.maxKeys = maxKeys;
    return this;
  }

  /** The token from the previous page's {@link S3ListObjectsResult#getNextContinuationToken()}. */
  public String getContinuationToken() {
    return continuationToken;
  }

  public S3ListObjectsOptions setContinuationToken(String continuationToken) {
    this.continuationToken = continuationToken;
    return this;
  }

  /** Lists only keys after this one (lexicographically); ignored by S3 when a continuation token is given. */
  public String getStartAfter() {
    return startAfter;
  }

  public S3ListObjectsOptions setStartAfter(String startAfter) {
    this.startAfter = startAfter;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ListObjectsOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ListObjectsOptions{prefix=" + prefix + ", delimiter=" + delimiter + ", maxKeys=" + maxKeys
      + ", continuationToken=" + continuationToken + ", startAfter=" + startAfter + '}';
  }
}
