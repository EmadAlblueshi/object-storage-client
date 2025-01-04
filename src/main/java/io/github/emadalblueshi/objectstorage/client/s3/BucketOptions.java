package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.MultiMap;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class BucketOptions extends S3RequestOptions {

  public BucketOptions() {
    super();
  }

  public BucketOptions bucketObjectLockEnabled(String value) {
    return (BucketOptions) super.putHeader("x-amz-bucket-object-lock-enabled", value);
  }

  public BucketOptions expectedBucketOwner(String value) {
    return (BucketOptions) super.expectedBucketOwner(value);
  }

  public BucketOptions policy() {
    return (BucketOptions) super.addQueryParam("policy", "");
  }

  public BucketOptions policyStatus() {
    return (BucketOptions) super.addQueryParam("policyStatus", "");
  }

  public BucketOptions maxKeys(int value) {
    return (BucketOptions) super.addQueryParam("max-keys", String.valueOf(value));
  }

  public BucketOptions prefix(String value) {
    return (BucketOptions) super.addQueryParam("prefix", value);
  }

  public BucketOptions delimiter(String value) {
    return (BucketOptions) super.addQueryParam("delimiter", value);
  }

  public BucketOptions marker(String value) {
    return (BucketOptions) super.addQueryParam("marker", value);
  }

  public BucketOptions allowUnordered(boolean value) {
    return (BucketOptions) super.addQueryParam("allow-unordered", String.valueOf(value));
  }

  public BucketOptions location() {
    return (BucketOptions) super.addQueryParam("location", "");
  }

  public BucketOptions keyMarker(String value) {
    return (BucketOptions) super.addQueryParam("key-marker", value);
  }

  public BucketOptions maxUploads(int value) {
    return (BucketOptions) super.addQueryParam("max-uploads", String.valueOf(value));
  }

  public BucketOptions uploadIdMarker(String value) {
    return (BucketOptions) super.addQueryParam("upload-id-marker", value);
  }

  public BucketOptions versioning() {
    return (BucketOptions) super.addQueryParam("versioning", "");
  }

  public BucketOptions objectLock() {
    return (BucketOptions) super.addQueryParam("object-lock", "");
  }

  public BucketOptions notification() {
    return (BucketOptions) super.addQueryParam("notification", "");
  }

  public BucketOptions notification(String id) {
    return (BucketOptions) super.addQueryParam("notification", id);
  }

  public BucketOptions acl(Acl acl) {
    return (BucketOptions) super.acl(acl);
  }

  public BucketOptions acl() {
    return (BucketOptions) super.acl();
  }

  public BucketOptions objectOwnership(ObjectOwnership objectOwnership) {
    return (BucketOptions) super.putHeader("x-amz-object-ownership", objectOwnership.toString());
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
