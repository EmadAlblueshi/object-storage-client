package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.MultiMap;
import static io.vertx.core.http.HttpHeaders.*;

/**
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectOptions extends S3RequestOptions {

  public ObjectOptions() {
    super();
  }

  public ObjectOptions expectedBucketOwner(String value) {
    return (ObjectOptions) super.expectedBucketOwner(value);
  }

  public ObjectOptions contentType(String contentType) {
    return (ObjectOptions) super.putHeader(CONTENT_TYPE, contentType);
  }

  // A string of base64 encoded MD-5 hash of the message.
  public ObjectOptions contentMD5(String contentMD5) {
    return (ObjectOptions) super.putHeader("content-md5", contentMD5);
  }

  public ObjectOptions storageClass(StorageClass storageClass) {
    return (ObjectOptions) super.putHeader("x-amz-storage-class", storageClass.toString());
  }

  // The source bucket name + object name. {bucket}/{object}
  public ObjectOptions copySource(String copySource) {
    return (ObjectOptions) super.putHeader("x-amz-copy-source", copySource);
  }

  // Copies only if modified since the timestamp. (optional)
  public ObjectOptions copyIfModifiedSince(String timestamp) {
    return (ObjectOptions) super.putHeader("x-amz-copy-if-modified-since", timestamp);
  }

  // Copies only if unmodified since the timestamp. (optional)
  public ObjectOptions copyIfUnmodifiedSince(String timestamp) {
    return (ObjectOptions) super.putHeader("x-amz-copy-if-unmodified-since", timestamp);
  }

  // Copies only if object ETag matches ETag.(optional)
  public ObjectOptions copyIfMatch(String entityTag) {
    return (ObjectOptions) super.putHeader("x-amz-copy-if-match", entityTag);
  }

  // Copies only if object ETag doesn’t match.
  public ObjectOptions copyIfNoneMatch(String entityTag) {
    return (ObjectOptions) super.putHeader("x-amz-copy-if-none-match", entityTag);
  }

  // The range of the object to retrieve.
  // bytes=beginbyte-endbyte
  // bytes=0-100
  public ObjectOptions range(int begin, int end) {
    return (ObjectOptions) super.putHeader("range", begin + "-" + end);
  }

  // Gets only if modified since the timestamp.
  public ObjectOptions ifModifiedSince(String timestamp) {
    return (ObjectOptions) super.putHeader("if-modified-since", timestamp);
  }

  // Gets only if not modified since the timestamp.
  public ObjectOptions ifUnmodifiedSince(String timestamp) {
    return (ObjectOptions) super.putHeader("if-modified-since", timestamp);
  }

  // Gets only if object ETag matches ETag.
  public ObjectOptions ifMatch(String entityTag) {
    return (ObjectOptions) super.putHeader("if-match", entityTag);
  }

  // Gets only if object ETag matches ETag.
  public ObjectOptions ifNoneMatch(String entityTag) {
    return (ObjectOptions) super.putHeader("if-none-match", entityTag);
  }

  public ObjectOptions partNumber(String no) {
    return (ObjectOptions) super.addQueryParam("partNumber", no);
  }

  public ObjectOptions uploadId(String id) {
    return (ObjectOptions) super.addQueryParam("uploadId", id);
  }

  public ObjectOptions append() {
    return (ObjectOptions) super.addQueryParam("append", "");
  }

  public ObjectOptions position(String value) {
    return (ObjectOptions) super.addQueryParam("position", value);
  }

  public ObjectOptions retention() {
    return (ObjectOptions) super.addQueryParam("retention", "");
  }

  public ObjectOptions versionId(String id) {
    return (ObjectOptions) super.addQueryParam("versionId", id);
  }

  public ObjectOptions legalHold() {
    return (ObjectOptions) super.addQueryParam("legal-hold", "");
  }

  // common

  // A canned ACL.
  public ObjectOptions acl(Acl acl) {
    return (ObjectOptions) super.acl(acl);
  }

  public ObjectOptions acl() {
    return (ObjectOptions) super.acl();
  }

  public ObjectOptions uploads() {
    return (ObjectOptions) super.uploads();
  }

  public ObjectOptions putHeader(String name, String value) {
    return (ObjectOptions) super.putHeader(name, value);
  }

  public ObjectOptions putHeaders(MultiMap headers) {
    return (ObjectOptions) super.putHeaders(headers);
  }

  public ObjectOptions addQueryParam(String name, String value) {
    return (ObjectOptions) super.addQueryParam(name, value);
  }

  public ObjectOptions addQueryParams(MultiMap queryParams) {
    return (ObjectOptions) super.addQueryParams(queryParams);
  }

}
