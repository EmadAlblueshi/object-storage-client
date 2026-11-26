package io.github.emadalblueshi.vertx.s3.impl;

/**
 * A response body is not acceptable XML: malformed, hostile (DTD or entities), or beyond the
 * parser limits. The transport (Phase 2 step 4) maps it to the public exception hierarchy.
 */
final class XmlParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  XmlParseException(String message) {
    super(message);
  }

  XmlParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
