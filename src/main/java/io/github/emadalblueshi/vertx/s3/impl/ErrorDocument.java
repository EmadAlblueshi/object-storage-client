package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed S3 {@code <Error>} document. Providers differ in what they include:
 * <ul>
 *   <li>AWS: {@code Code}, {@code Message}, {@code RequestId}, {@code HostId}, and often
 *   {@code Resource}, {@code Key}, {@code BucketName}, or {@code Endpoint} for redirects.</li>
 *   <li>MinIO-compatible servers (Silo) add {@code Region}.</li>
 *   <li>Ceph RGW-based servers (e.g. DigitalOcean Spaces) may leave {@code Message} empty.</li>
 * </ul>
 * So every field may be {@code null}. Leaf elements other than the five standard ones are kept,
 * in order, in {@link #details()}.
 */
final class ErrorDocument {

  private final String code;
  private final String message;
  private final String resource;
  private final String requestId;
  private final String hostId;
  private final Map<String, String> details;

  ErrorDocument(String code, String message, String resource, String requestId, String hostId,
                Map<String, String> details) {
    this.code = code;
    this.message = message;
    this.resource = resource;
    this.requestId = requestId;
    this.hostId = hostId;
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  String code() {
    return code;
  }

  String message() {
    return message;
  }

  String resource() {
    return resource;
  }

  String requestId() {
    return requestId;
  }

  String hostId() {
    return hostId;
  }

  /** Other leaf elements, e.g. {@code Region}, {@code BucketName}, {@code Key}, {@code Endpoint}. */
  Map<String, String> details() {
    return details;
  }

  @Override
  public String toString() {
    return "ErrorDocument{code=" + code + ", message=" + message + ", resource=" + resource
      + ", requestId=" + requestId + ", hostId=" + hostId + ", details=" + details + '}';
  }
}
