package io.github.emadalblueshi.objectstorage.client;

import io.vertx.core.MultiMap;

/**
 *
 * The object storage request options has common options between Object and
 * Bucket.
 * <p>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectStorageRequestOptions {

  protected final MultiMap headers;
  protected final MultiMap queryParams;

  public ObjectStorageRequestOptions() {
    headers = MultiMap.caseInsensitiveMultiMap();
    queryParams = MultiMap.caseInsensitiveMultiMap();
  }

  public ObjectStorageRequestOptions acl(Acl acl) {
    headers.set("x-amz-acl", acl.toString());
    return this;
  }

  public ObjectStorageRequestOptions acl() {
    queryParams.set("acl", "");
    return this;
  }

  public ObjectStorageRequestOptions uploads() {
    queryParams.set("uploads", "");
    return this;
  }

  public ObjectStorageRequestOptions putHeader(String name, String value) {
    headers.set(name, value);
    return this;
  }

  public ObjectStorageRequestOptions putHeaders(MultiMap headers) {
    headers.addAll(headers);
    return this;
  }

  public ObjectStorageRequestOptions addQueryParam(String name, String value) {
    queryParams.set(name, value);
    return this;
  }

  public ObjectStorageRequestOptions addQueryParams(MultiMap queryParams) {
    queryParams.addAll(queryParams);
    return this;
  }

  public MultiMap headers() {
    return headers;
  }

  public MultiMap queryParams() {
    return queryParams;
  }

}
