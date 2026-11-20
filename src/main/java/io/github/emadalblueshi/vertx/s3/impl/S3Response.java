package io.github.emadalblueshi.vertx.s3.impl;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * A complete (buffered) HTTP response from S3.
 */
final class S3Response {

  final int statusCode;
  final String statusMessage;
  final MultiMap headers;
  final Buffer body;

  S3Response(int statusCode, String statusMessage, MultiMap headers, Buffer body) {
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
    this.headers = headers;
    this.body = body;
  }

  boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
