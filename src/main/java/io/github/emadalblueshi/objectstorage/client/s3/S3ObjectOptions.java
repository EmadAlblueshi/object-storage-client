package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import static io.vertx.core.http.HttpHeaders.*;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;

/**
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3ObjectOptions extends S3RequestOptions {

  public S3ObjectOptions() {
    super();
  }

  public S3ObjectOptions(JsonObject json) {
    S3ObjectOptionsConverter.fromJson(json, this);
  }

  public S3ObjectOptions expectedBucketOwner(String value) {
    return (S3ObjectOptions) super.expectedBucketOwner(value);
  }

  public S3ObjectOptions contentType(String contentType) {
    return (S3ObjectOptions) super.putHeader(CONTENT_TYPE, contentType);
  }

  // A string of base64 encoded MD-5 hash of the message.
  public S3ObjectOptions contentMD5(String contentMD5) {
    return (S3ObjectOptions) super.putHeader("content-md5", contentMD5);
  }

  public S3ObjectOptions storageClass(S3StorageClass s3StorageClass) {
    return (S3ObjectOptions) super.putHeader("x-amz-storage-class", s3StorageClass.toString());
  }

  // The source bucket name + object name. {bucket}/{object}
  public S3ObjectOptions copySource(String copySource) {
    return (S3ObjectOptions) super.putHeader("x-amz-copy-source", copySource);
  }

  // Copies only if modified since the timestamp. (optional)
  public S3ObjectOptions copyIfModifiedSince(String timestamp) {
    return (S3ObjectOptions) super.putHeader("x-amz-copy-if-modified-since", timestamp);
  }

  // Copies only if unmodified since the timestamp. (optional)
  public S3ObjectOptions copyIfUnmodifiedSince(String timestamp) {
    return (S3ObjectOptions) super.putHeader("x-amz-copy-if-unmodified-since", timestamp);
  }

  // Copies only if object ETag matches ETag.(optional)
  public S3ObjectOptions copyIfMatch(String entityTag) {
    return (S3ObjectOptions) super.putHeader("x-amz-copy-if-match", entityTag);
  }

  // Copies only if object ETag doesn’t match.
  public S3ObjectOptions copyIfNoneMatch(String entityTag) {
    return (S3ObjectOptions) super.putHeader("x-amz-copy-if-none-match", entityTag);
  }

  // The range of the object to retrieve.
  // bytes=beginbyte-endbyte
  // bytes=0-100
  public S3ObjectOptions range(int begin, int end) {
    return (S3ObjectOptions) super.putHeader("range", begin + "-" + end);
  }

  // Gets only if modified since the timestamp.
  public S3ObjectOptions ifModifiedSince(String timestamp) {
    return (S3ObjectOptions) super.putHeader("if-modified-since", timestamp);
  }

  // Gets only if not modified since the timestamp.
  public S3ObjectOptions ifUnmodifiedSince(String timestamp) {
    return (S3ObjectOptions) super.putHeader("if-modified-since", timestamp);
  }

  // Gets only if object ETag matches ETag.
  public S3ObjectOptions ifMatch(String entityTag) {
    return (S3ObjectOptions) super.putHeader("if-match", entityTag);
  }

  // Gets only if object ETag matches ETag.
  public S3ObjectOptions ifNoneMatch(String entityTag) {
    return (S3ObjectOptions) super.putHeader("if-none-match", entityTag);
  }

  public S3ObjectOptions partNumber(String no) {
    return (S3ObjectOptions) super.addQueryParam("partNumber", no);
  }

  public S3ObjectOptions uploadId(String id) {
    return (S3ObjectOptions) super.addQueryParam("uploadId", id);
  }

  public S3ObjectOptions append() {
    return (S3ObjectOptions) super.addQueryParam("append", "");
  }

  public S3ObjectOptions position(String value) {
    return (S3ObjectOptions) super.addQueryParam("position", value);
  }

  public S3ObjectOptions retention() {
    return (S3ObjectOptions) super.addQueryParam("retention", "");
  }

  public S3ObjectOptions versionId(String id) {
    return (S3ObjectOptions) super.addQueryParam("versionId", id);
  }

  public S3ObjectOptions legalHold() {
    return (S3ObjectOptions) super.addQueryParam("legal-hold", "");
  }

  // common

  // A canned ACL.
  public S3ObjectOptions acl(S3Acl s3Acl) {
    return (S3ObjectOptions) super.acl(s3Acl);
  }

  public S3ObjectOptions acl() {
    return (S3ObjectOptions) super.acl();
  }

  public S3ObjectOptions uploads() {
    return (S3ObjectOptions) super.uploads();
  }

  public S3ObjectOptions putHeader(String name, String value) {
    return (S3ObjectOptions) super.putHeader(name, value);
  }

  public S3ObjectOptions putHeaders(MultiMap headers) {
    return (S3ObjectOptions) super.putHeaders(headers);
  }

  public S3ObjectOptions addQueryParam(String name, String value) {
    return (S3ObjectOptions) super.addQueryParam(name, value);
  }

  public S3ObjectOptions addQueryParam(MultiMap queryParams) {
    return (S3ObjectOptions) super.addQueryParam(queryParams);
  }

}
