package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.VertxGen;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public enum S3StorageClass {
  STANDARD("STANDARD");

  private final String value;

  S3StorageClass(String val) {
    value = val;
  }

  @Override
  public String toString() {
    return value;
  }
}
