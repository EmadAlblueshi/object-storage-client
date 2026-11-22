package io.github.emadalblueshi.vertx.s3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * A presigned request: what an app needs to upload or download one object without credentials.
 * Send {@link #getMethod() method} to {@link #getUrl() url} with exactly the
 * {@link #getHeaders() headers} listed. {@link #toJson()} gives {@code {"url", "method", "headers"}},
 * e.g. to return it to the app as is.
 * <p>
 * The URL is a bearer token until it expires: never log it. {@link #toString()} leaves it out;
 * {@link #toJson()} includes it, since handing it out is its purpose.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3PresignedRequest {

  private String url;
  private String method;
  private Map<String, String> headers = new LinkedHashMap<>();

  public S3PresignedRequest() {
  }

  public S3PresignedRequest(S3PresignedRequest other) {
    this.url = other.url;
    this.method = other.method;
    this.headers = new LinkedHashMap<>(other.headers);
  }

  public S3PresignedRequest(JsonObject json) {
    S3PresignedRequestConverter.fromJson(json, this);
  }

  /** The presigned URL. */
  public String getUrl() {
    return url;
  }

  public S3PresignedRequest setUrl(String url) {
    this.url = url;
    return this;
  }

  /** The HTTP method to use: {@code PUT} or {@code GET}. */
  public String getMethod() {
    return method;
  }

  public S3PresignedRequest setMethod(String method) {
    this.method = method;
    return this;
  }

  /**
   * The headers the request must send exactly as given, because they are signed (e.g.
   * {@code Content-Type}, {@code Content-MD5}); empty when there are none. Unmodifiable.
   */
  public Map<String, String> getHeaders() {
    return Collections.unmodifiableMap(headers);
  }

  /** Replaces the headers with a copy of {@code headers}. */
  public S3PresignedRequest setHeaders(Map<String, String> headers) {
    this.headers = new LinkedHashMap<>(Objects.requireNonNull(headers, "headers"));
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PresignedRequestConverter.toJson(this, json);
    return json;
  }

  /** Never includes the URL. */
  @Override
  public String toString() {
    return "S3PresignedRequest{method=" + method + ", headers=" + headers + '}';
  }
}
