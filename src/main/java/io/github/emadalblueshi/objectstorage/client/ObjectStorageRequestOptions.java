package io.github.emadalblueshi.objectstorage.client;

import io.netty.handler.codec.http.QueryStringEncoder;
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

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((headers == null) ? 0 : headers.hashCode());
    result = prime * result + ((queryParams == null) ? 0 : queryParams.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    ObjectStorageRequestOptions other = (ObjectStorageRequestOptions) obj;
    if (headers == null) {
      if (other.headers != null)
        return false;
    } else if (!headers.equals(other.headers))
      return false;
    if (queryParams == null) {
      if (other.queryParams != null)
        return false;
    } else if (!queryParams.equals(other.queryParams))
      return false;
    return true;
  }

  @Override
  public String toString() {
    QueryStringEncoder queryStringEncoder = new QueryStringEncoder("");
    queryParams.entries().forEach(e -> queryStringEncoder.addParam(e.getKey(), e.getValue()));
    return queryStringEncoder.toString().replace("?", "");

  }

}
