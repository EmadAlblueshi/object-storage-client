package io.github.emadalblueshi.objectstorage.client;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

/**
 *
 * The object storage request options.
 * <p>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class ObjectStorageRequestOptions {

  private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
  private final MultiMap queryParams = MultiMap.caseInsensitiveMultiMap();

  public ObjectStorageRequestOptions() {
    super();
  }

  public ObjectStorageRequestOptions(JsonObject json) {
    ObjectStorageRequestOptionsConverter.fromJson(json, this);
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

  public ObjectStorageRequestOptions addQueryParam(MultiMap queryParams) {
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
