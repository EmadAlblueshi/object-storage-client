package io.github.emadalblueshi.objectstorage.client.s3;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageRequestOptions;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3RequestOptions extends ObjectStorageRequestOptions {

  public S3RequestOptions() {
    super();
  }

  public S3RequestOptions(JsonObject json) {
    S3RequestOptionsConverter.fromJson(json, this);
  }

  public S3RequestOptions acl(Acl acl) {
    return (S3RequestOptions) super.putHeader("x-amz-acl", acl.toString());
  }

  public S3RequestOptions acl() {
    return (S3RequestOptions) super.addQueryParam("acl", "");
  }

  public S3RequestOptions uploads() {
    return (S3RequestOptions) super.addQueryParam("uploads", "");
  }

  public S3RequestOptions expectedBucketOwner(String value) {
    return (S3RequestOptions) super.putHeader("x-amz-expected-bucket-owner", value);
  }
}
