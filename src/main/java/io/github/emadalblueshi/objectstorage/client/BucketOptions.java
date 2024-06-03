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
    headers.set("x-amz-bucket-object-lock-enabled", value);
    return this;
  }

  public BucketOptions maxKeys(int value) {
    queryParams.set("max-keys", String.valueOf(value));
    return this;
  }

  public BucketOptions prefix(String value) {
    queryParams.set("prefix", value);
    return this;
  }

  public BucketOptions delimiter(String value) {
    queryParams.set("delimiter", value);
    return this;
  }

  public BucketOptions marker(String value) {
    queryParams.set("marker", value);
    return this;
  }

  public BucketOptions allowUnordered(boolean value) {
    queryParams.set("allow-unordered", String.valueOf(value));
    return this;
  }

  public BucketOptions location() {
    queryParams.set("location", "");
    return this;
  }

  public BucketOptions keyMarker(String value) {
    queryParams.set("key-marker", value);
    return this;
  }

  public BucketOptions maxUploads(int value) {
    queryParams.set("max-uploads", String.valueOf(value));
    return this;
  }

  public BucketOptions uploadIdMarker(String value) {
    queryParams.set("upload-id-marker", value);
    return this;
  }

  public BucketOptions versioning() {
    queryParams.set("versioning", "");
    return this;
  }

  public BucketOptions objectLock() {
    queryParams.set("object-lock", "");
    return this;
  }

  public BucketOptions notification() {
    queryParams.set("notification", "");
    return this;
  }

  public BucketOptions notification(String id) {
    queryParams.set("notification", id);
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
