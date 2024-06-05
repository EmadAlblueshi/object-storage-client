package io.github.emadalblueshi.objectstorage.client;

import io.vertx.core.MultiMap;
import static io.vertx.core.http.HttpHeaders.*;

/**
 *
 * The Object request options has all required and optional headers with query
 * params.
 * this will reduce potential verbosity for Object operations.
 * <p>
 * see <a href="https://docs.ceph.com/en/reef/radosgw/s3/objectops/">Object
 * Operations</a>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectOptions extends ObjectStorageRequestOptions {

  public ObjectOptions() {
    super();
  }

  public ObjectOptions contentType(String contentType) {
    super.putHeader(CONTENT_TYPE, contentType);
    return this;
  }

  // A string of base64 encoded MD-5 hash of the message.
  public ObjectOptions contentMD5(String contentMD5) {
    super.putHeader("content-md5", contentMD5);
    return this;
  }

  public ObjectOptions storageClass(StorageClass storageClass) {
    super.putHeader("x-amz-storage-class", storageClass.toString());
    return this;
  }

  // The source bucket name + object name. {bucket}/{object}
  public ObjectOptions copySource(String copySource) {
    super.putHeader("x-amz-copy-source", copySource);
    return this;
  }

  // Copies only if modified since the timestamp. (optional)
  public ObjectOptions copyIfModifiedSince(String timestamp) {
    super.putHeader("x-amz-copy-if-modified-since", timestamp);
    return this;
  }

  // Copies only if unmodified since the timestamp. (optional)
  public ObjectOptions copyIfUnmodifiedSince(String timestamp) {
    super.putHeader("x-amz-copy-if-unmodified-since", timestamp);
    return this;
  }

  // Copies only if object ETag matches ETag.(optional)
  public ObjectOptions copyIfMatch(String entityTag) {
    super.putHeader("x-amz-copy-if-match", entityTag);
    return this;
  }

  // Copies only if object ETag doesn’t match.
  public ObjectOptions copyIfNoneMatch(String entityTag) {
    super.putHeader("x-amz-copy-if-none-match", entityTag);
    return this;
  }

  // The range of the object to retrieve.
  // bytes=beginbyte-endbyte
  // bytes=0-100
  public ObjectOptions range(int begin, int end) {
    super.putHeader("range", begin + "-" + end);
    return this;
  }

  // Gets only if modified since the timestamp.
  public ObjectOptions ifModifiedSince(String timestamp) {
    super.putHeader("if-modified-since", timestamp);
    return this;
  }

  // Gets only if not modified since the timestamp.
  public ObjectOptions ifUnmodifiedSince(String timestamp) {
    super.putHeader("if-modified-since", timestamp);
    return this;
  }

  // Gets only if object ETag matches ETag.
  public ObjectOptions ifMatch(String entityTag) {
    super.putHeader("if-match", entityTag);
    return this;
  }

  // Gets only if object ETag matches ETag.
  public ObjectOptions ifNoneMatch(String entityTag) {
    super.putHeader("if-none-match", entityTag);
    return this;
  }

  public ObjectOptions partNumber(String no) {
    super.addQueryParam("partNumber", no);
    return this;
  }

  public ObjectOptions uploadId(String id) {
    super.addQueryParam("uploadId", id);
    return this;
  }

  public ObjectOptions append() {
    super.addQueryParam("append", "");
    return this;
  }

  public ObjectOptions position(String value) {
    super.addQueryParam("position", value);
    return this;
  }

  public ObjectOptions retention() {
    super.addQueryParam("retention", "");
    return this;
  }

  public ObjectOptions versionId(String id) {
    super.addQueryParam("versionId", id);
    return this;
  }

  public ObjectOptions legalHold() {
    super.addQueryParam("legal-hold", "");
    return this;
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
