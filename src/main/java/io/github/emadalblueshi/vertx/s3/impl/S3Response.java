package io.github.emadalblueshi.vertx.s3.impl;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * A complete (buffered) HTTP response from S3, with the secrets of the request it answers, which
 * an exception built from it must not contain.
 */
final class S3Response {

  final int statusCode;
  final String statusMessage;
  final MultiMap headers;
  final Buffer body;
  final Redaction redaction;

  S3Response(int statusCode, String statusMessage, MultiMap headers, Buffer body, Redaction redaction) {
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
    this.headers = headers;
    this.body = body;
    this.redaction = redaction;
  }

  boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
