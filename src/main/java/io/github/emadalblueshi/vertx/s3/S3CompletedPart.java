package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * An uploaded part, as passed to {@link S3Client#completeMultipartUpload}: its number and the
 * {@code ETag} the server returned when the part was uploaded.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3CompletedPart {

  private int partNumber;
  private String etag;

  public S3CompletedPart() {
  }

  /**
   * @param partNumber the part number, 1 to 10,000
   * @param etag the part's {@code ETag}, as returned by the server (with or without quotes)
   */
  public S3CompletedPart(int partNumber, String etag) {
    this.partNumber = partNumber;
    this.etag = etag;
  }

  public S3CompletedPart(S3CompletedPart other) {
    this.partNumber = other.partNumber;
    this.etag = other.etag;
  }

  public S3CompletedPart(JsonObject json) {
    S3CompletedPartConverter.fromJson(json, this);
  }

  /** The part number, 1 to 10,000. */
  public int getPartNumber() {
    return partNumber;
  }

  public S3CompletedPart setPartNumber(int partNumber) {
    this.partNumber = partNumber;
    return this;
  }

  /** The part's {@code ETag}, as returned by the server when the part was uploaded. */
  public String getEtag() {
    return etag;
  }

  public S3CompletedPart setEtag(String etag) {
    this.etag = etag;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3CompletedPartConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3CompletedPart{partNumber=" + partNumber + ", etag=" + etag + '}';
  }
}
