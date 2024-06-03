package io.github.emadalblueshi.objectstorage.client;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public enum StorageClass {
  STANDARD("STANDARD");

  private final String value;

  StorageClass(String val) {
    value = val;
  }

  @Override
  public String toString() {
    return value;
  }
}
