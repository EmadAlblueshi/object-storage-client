package io.github.emadalblueshi.objectstorage.client;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public enum Acl {
  PUBLIC("public"),
  PUBLIC_READ("public-read"),
  PRIVATE("private"),
  AUTHENTICATED_READ("authenticated-read"),
  PUBLIC_READ_WRITE("public-read-write");

  private final String value;

  Acl(String val) {
    value = val;
  }

  @Override
  public String toString() {
    return value;
  }
}
