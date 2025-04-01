package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3BucketOptions extends S3RequestOptions {

  public S3BucketOptions() {
    super();
  }

  public S3BucketOptions(JsonObject json) {
    S3BucketOptionsConverter.fromJson(json, this);
  }

  public S3BucketOptions bucketObjectLockEnabled(String value) {
    return (S3BucketOptions) super.putHeader("x-amz-bucket-object-lock-enabled", value);
  }

  public S3BucketOptions expectedBucketOwner(String value) {
    return (S3BucketOptions) super.expectedBucketOwner(value);
  }

  public S3BucketOptions policy() {
    return (S3BucketOptions) super.addQueryParam("policy", "");
  }

  public S3BucketOptions policyStatus() {
    return (S3BucketOptions) super.addQueryParam("policyStatus", "");
  }

  public S3BucketOptions maxKeys(int value) {
    return (S3BucketOptions) super.addQueryParam("max-keys", String.valueOf(value));
  }

  public S3BucketOptions prefix(String value) {
    return (S3BucketOptions) super.addQueryParam("prefix", value);
  }

  public S3BucketOptions delimiter(String value) {
    return (S3BucketOptions) super.addQueryParam("delimiter", value);
  }

  public S3BucketOptions marker(String value) {
    return (S3BucketOptions) super.addQueryParam("marker", value);
  }

  public S3BucketOptions allowUnordered(boolean value) {
    return (S3BucketOptions) super.addQueryParam("allow-unordered", String.valueOf(value));
  }

  public S3BucketOptions location() {
    return (S3BucketOptions) super.addQueryParam("location", "");
  }

  public S3BucketOptions keyMarker(String value) {
    return (S3BucketOptions) super.addQueryParam("key-marker", value);
  }

  public S3BucketOptions maxUploads(int value) {
    return (S3BucketOptions) super.addQueryParam("max-uploads", String.valueOf(value));
  }

  public S3BucketOptions uploadIdMarker(String value) {
    return (S3BucketOptions) super.addQueryParam("upload-id-marker", value);
  }

  public S3BucketOptions versioning() {
    return (S3BucketOptions) super.addQueryParam("versioning", "");
  }

  public S3BucketOptions objectLock() {
    return (S3BucketOptions) super.addQueryParam("object-lock", "");
  }

  public S3BucketOptions notification() {
    return (S3BucketOptions) super.addQueryParam("notification", "");
  }

  public S3BucketOptions notification(String id) {
    return (S3BucketOptions) super.addQueryParam("notification", id);
  }

  public S3BucketOptions acl(S3Acl s3Acl) {
    return (S3BucketOptions) super.acl(s3Acl);
  }

  public S3BucketOptions acl() {
    return (S3BucketOptions) super.acl();
  }

  public S3BucketOptions objectOwnership(S3ObjectOwnership s3ObjectOwnership) {
    return (S3BucketOptions) super.putHeader("x-amz-object-ownership", s3ObjectOwnership.toString());
  }

  public S3BucketOptions uploads() {
    return (S3BucketOptions) super.uploads();
  }

  public S3BucketOptions putHeader(String name, String value) {
    return (S3BucketOptions) super.putHeader(name, value);
  }

  public S3BucketOptions putHeaders(MultiMap headers) {
    return (S3BucketOptions) super.putHeaders(headers);
  }

  public S3BucketOptions addQueryParam(String name, String value) {
    return (S3BucketOptions) super.addQueryParam(name, value);
  }

  public S3BucketOptions addQueryParam(MultiMap queryParams) {
    return (S3BucketOptions) super.addQueryParam(queryParams);
  }

}
