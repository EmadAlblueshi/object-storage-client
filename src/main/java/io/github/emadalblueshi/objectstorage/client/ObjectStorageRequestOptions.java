package io.github.emadalblueshi.objectstorage.client;

import io.vertx.core.MultiMap;

/**
 *
 * The object storage request options.
 * <p>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectStorageRequestOptions {

  private final MultiMap headers;
  private final MultiMap queryParams;

  public ObjectStorageRequestOptions() {
    headers = MultiMap.caseInsensitiveMultiMap();
    queryParams = MultiMap.caseInsensitiveMultiMap();
  }

  public ObjectStorageRequestOptions putHeader(CharSequence name, String value) {
    headers.set(name, value);
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

  public ObjectStorageRequestOptions addQueryParam(CharSequence name, String value) {
    queryParams.set(name, value);
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
