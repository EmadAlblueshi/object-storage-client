package io.github.emadalblueshi.objectstorage.client;

import io.github.emadalblueshi.objectstorage.client.s3.S3Client;
import io.github.emadalblueshi.objectstorage.client.s3.impl.S3ClientImpl;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Vertx;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */
@VertxGen
public interface ObjectStorageClient {

  static S3Client s3Create(Vertx vertx, ObjectStorageClientOptions objectStorageClientOptions) {
    return new S3ClientImpl(vertx, objectStorageClientOptions);
  }

  void close();
}
