package io.github.emadalblueshi.objectstorage.client;

import io.vertx.ext.web.client.HttpResponse;

/**
 * Most common used headers returned from
 * the object storage server in order to reduce API verbosity.
 * <p>
 * 
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public interface ObjectResponse<T> extends HttpResponse<T> {
  String requestId();

  String ETag();

  String contentType();

  String storageClass();

  String contentRange();

  String nextAppendPosition();

}
