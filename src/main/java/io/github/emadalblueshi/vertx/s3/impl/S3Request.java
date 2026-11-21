package io.github.emadalblueshi.vertx.s3.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/**
 * An S3 request before addressing and signing. Immutable.
 */
final class S3Request {

  final HttpMethod method;
  /** The bucket, or {@code null} for service-level requests (e.g. ListBuckets). */
  final String bucket;
  /** The object key, or {@code null}. */
  final String key;
  final List<Map.Entry<String, String>> query;
  /** Extra headers to send and sign (never signer-managed headers). */
  final List<Map.Entry<String, String>> headers;
  /** A buffered body, or {@code null}. */
  final Buffer body;

  S3Request(HttpMethod method, String bucket, String key, List<Map.Entry<String, String>> query,
            List<Map.Entry<String, String>> headers, Buffer body) {
    this.method = Objects.requireNonNull(method, "method");
    if (key != null && bucket == null) {
      throw new IllegalArgumentException("a key needs a bucket");
    }
    this.bucket = bucket;
    this.key = key;
    this.query = List.copyOf(query);
    this.headers = List.copyOf(headers);
    this.body = body;
  }

  /** A service-level request without query, headers or body. */
  static S3Request service(HttpMethod method) {
    return new S3Request(method, null, null, List.of(), List.of(), null);
  }

  /** An object request without query; {@code body} may be {@code null}. */
  static S3Request object(HttpMethod method, String bucket, String key, List<Map.Entry<String, String>> headers,
                          Buffer body) {
    return new S3Request(method, Objects.requireNonNull(bucket, "bucket"), Objects.requireNonNull(key, "key"),
      List.of(), headers, body);
  }

  /** A bucket-level request without query or headers; {@code body} may be {@code null}. */
  static S3Request bucket(HttpMethod method, String bucket, Buffer body) {
    return new S3Request(method, Objects.requireNonNull(bucket, "bucket"), null, List.of(), List.of(), body);
  }
}
