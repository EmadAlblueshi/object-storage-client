package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.CacheReturn;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.ext.web.client.HttpResponse;

/**
 * Most common used headers returned from
 * the object storage server in order to reduce API verbosity.
 * <p>
 * 
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public interface S3Response<T> extends HttpResponse<T> {

  @CacheReturn
  String requestId();

  @Nullable
  String ETag();

  @Nullable
  String contentType();

  @Nullable
  String storageClass();

  @Nullable
  String contentRange();

  @Nullable
  String nextAppendPosition();

}
