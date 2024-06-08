package io.github.emadalblueshi.objectstorage.client.s3;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageRequestOptions;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class S3RequestOptions extends ObjectStorageRequestOptions {

  public S3RequestOptions() {
    super();
  }

  public S3RequestOptions acl(Acl acl) {
    return (S3RequestOptions) super.putHeader("x-amz-acl", acl.toString());
  }

  public S3RequestOptions acl() {
    return (S3RequestOptions) super.addQueryParam("acl", "");
  }

  public ObjectStorageRequestOptions uploads() {
    return (S3RequestOptions) super.addQueryParam("uploads", "");
  }
}
