package io.github.emadalblueshi.objectstorage.client.s3.impl;

import java.util.List;

import io.github.emadalblueshi.objectstorage.client.s3.S3Response;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.client.impl.HttpResponseImpl;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class S3ResponseImpl<T> extends HttpResponseImpl<T> implements S3Response<T> {

  public S3ResponseImpl(HttpVersion version,
      int statusCode,
      String statusMessage,
      MultiMap headers,
      MultiMap trailers,
      List<String> cookies,
      T body, List<String> redirects) {
    super(version, statusCode, statusMessage, headers, trailers, cookies, body, redirects);
  }

  @Override
  public MultiMap trailers() {
    return super.trailers();
  }

  @Override
  public String getTrailer(String trailerName) {
    return super.getTrailer(trailerName);
  }

  @Override
  public T body() {
    return super.body();
  }

  @Override
  public Buffer bodyAsBuffer() {
    return super.bodyAsBuffer();
  }

  @Override
  public List<String> followedRedirects() {
    return super.followedRedirects();
  }

  @Override
  public JsonArray bodyAsJsonArray() {
    return super.bodyAsJsonArray();
  }

  @Override
  public HttpVersion version() {
    return super.version();
  }

  @Override
  public int statusCode() {
    return super.statusCode();
  }

  @Override
  public String statusMessage() {
    return super.statusMessage();
  }

  @Override
  public MultiMap headers() {
    return super.headers();
  }

  @Override
  public String getHeader(String headerName) {
    return super.getHeader(headerName);
  }

  @Override
  public String getHeader(CharSequence headerName) {
    return super.getHeader(headerName);
  }

  @Override
  public List<String> cookies() {
    return super.cookies();
  }

  @Override
  public String requestId() {
    return super.getHeader("x-amz-request-id");
  }

  @Override
  public String ETag() {
    return super.getHeader("etag");
  }

  @Override
  public String contentType() {
    return super.getHeader("content-type");
  }

  @Override
  public String contentLength() {
    return super.getHeader("content-length");
  }

  @Override
  public String storageClass() {
    return super.getHeader("x-amz-storage-class");
  }

  @Override
  public String contentRange() {
    return super.getHeader("content-range");
  }

  @Override
  public String bucketRegion() {
    return super.getHeader("x-amz-bucket-region");
  }
}
