package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.VertxGen;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public enum S3Acl {
  PUBLIC("public"),
  PUBLIC_READ("public-read"),
  PRIVATE("private"),
  AUTHENTICATED_READ("authenticated-read"),
  PUBLIC_READ_WRITE("public-read-write");

  private final String value;

  S3Acl(String val) {
    value = val;
  }

  @Override
  public String toString() {
    return value;
  }
}
