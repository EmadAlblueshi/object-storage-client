package io.github.emadalblueshi.objectstorage.client;

import io.vertx.core.MultiMap;

/**
 *
 * The Bucket request options has all required and optional headers with query
 * params.
 * this will reduce potential verbosity for Bucket operations.
 * <p>
 * see <a href="https://docs.ceph.com/en/reef/radosgw/s3/bucketops/">Bucket
 * Operations</a>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class BucketOptions extends ObjectStorageRequestOptions {

  public BucketOptions() {
    super();
  }

  public BucketOptions bucketObjectLockEnabled(String value) {
    super.putHeader("x-amz-bucket-object-lock-enabled", value);
    return this;
  }

  public BucketOptions maxKeys(int value) {
    super.addQueryParam("max-keys", String.valueOf(value));
    return this;
  }

  public BucketOptions prefix(String value) {
    super.addQueryParam("prefix", value);
    return this;
  }

  public BucketOptions delimiter(String value) {
    super.addQueryParam("delimiter", value);
    return this;
  }

  public BucketOptions marker(String value) {
    super.addQueryParam("marker", value);
    return this;
  }

  public BucketOptions allowUnordered(boolean value) {
    super.addQueryParam("allow-unordered", String.valueOf(value));
    return this;
  }

  public BucketOptions location() {
    super.addQueryParam("location", "");
    return this;
  }

  public BucketOptions keyMarker(String value) {
    super.addQueryParam("key-marker", value);
    return this;
  }

  public BucketOptions maxUploads(int value) {
    super.addQueryParam("max-uploads", String.valueOf(value));
    return this;
  }

  public BucketOptions uploadIdMarker(String value) {
    super.addQueryParam("upload-id-marker", value);
    return this;
  }

  public BucketOptions versioning() {
    super.addQueryParam("versioning", "");
    return this;
  }

  public BucketOptions objectLock() {
    super.addQueryParam("object-lock", "");
    return this;
  }

  public BucketOptions notification() {
    super.addQueryParam("notification", "");
    return this;
  }

  public BucketOptions notification(String id) {
    super.addQueryParam("notification", id);
    return this;
  }

  // common

  // A canned ACL.
  public BucketOptions acl(Acl acl) {
    return (BucketOptions) super.acl(acl);
  }

  public BucketOptions acl() {
    return (BucketOptions) super.acl();
  }

  public BucketOptions uploads() {
    return (BucketOptions) super.uploads();
  }

  public BucketOptions putHeader(String name, String value) {
    return (BucketOptions) super.putHeader(name, value);
  }

  public BucketOptions putHeaders(MultiMap headers) {
    return (BucketOptions) super.putHeaders(headers);
  }

  public BucketOptions addQueryParam(String name, String value) {
    return (BucketOptions) super.addQueryParam(name, value);
  }

  public BucketOptions addQueryParams(MultiMap queryParams) {
    return (BucketOptions) super.addQueryParams(queryParams);
  }

}
