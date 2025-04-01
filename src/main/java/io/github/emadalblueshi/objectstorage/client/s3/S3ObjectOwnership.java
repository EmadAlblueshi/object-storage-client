package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.VertxGen;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public enum S3ObjectOwnership {
  BUCKET_OWNER_PREFERRED("BucketOwnerPreferred"),
  OBJECT_WRITER("ObjectWriter"),
  BUCKET_OWNER_ENFORCED("BucketOwnerEnforced");

  private final String value;

  S3ObjectOwnership(String val) {
    value = val;
  }

  @Override
  public String toString() {
    return value;
  }
}
