package io.github.emadalblueshi.vertx.s3;

import java.time.Instant;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/** A part of a multipart upload, as listed by {@link S3Client#listParts}. */
@DataObject
@JsonGen(publicConverter = false)
public class S3UploadedPart {

  private int partNumber;
  private String etag;
  private long size;
  private Instant lastModified;

  public S3UploadedPart() {
  }

  public S3UploadedPart(S3UploadedPart other) {
    this.partNumber = other.partNumber;
    this.etag = other.etag;
    this.size = other.size;
    this.lastModified = other.lastModified;
  }

  public S3UploadedPart(JsonObject json) {
    S3UploadedPartConverter.fromJson(json, this);
  }

  /** The part number. */
  public int getPartNumber() {
    return partNumber;
  }

  public S3UploadedPart setPartNumber(int partNumber) {
    this.partNumber = partNumber;
    return this;
  }

  /** The part's {@code ETag}, to pass to {@link S3Client#completeMultipartUpload}. */
  public String getEtag() {
    return etag;
  }

  public S3UploadedPart setEtag(String etag) {
    this.etag = etag;
    return this;
  }

  /** The part's size in bytes. */
  public long getSize() {
    return size;
  }

  public S3UploadedPart setSize(long size) {
    this.size = size;
    return this;
  }

  /** When the part was uploaded, or {@code null} if the server didn't say. */
  public Instant getLastModified() {
    return lastModified;
  }

  public S3UploadedPart setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3UploadedPartConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3UploadedPart{partNumber=" + partNumber + ", etag=" + etag + ", size=" + size + ", lastModified="
      + lastModified + '}';
  }
}
