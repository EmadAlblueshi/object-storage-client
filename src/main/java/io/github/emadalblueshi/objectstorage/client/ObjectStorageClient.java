package io.github.emadalblueshi.objectstorage.client;

import io.github.emadalblueshi.objectstorage.client.impl.ObjectStorageClientImpl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public interface ObjectStorageClient {

  static ObjectStorageClient create(Vertx vertx, ObjectStorageClientOptions objectStorageClientOptions) {
    return new ObjectStorageClientImpl(vertx, objectStorageClientOptions);
  }

  Future<ObjectResponse<Void>> put(ObjectOptions objectOptions, String path, Buffer object);

  Future<ObjectResponse<Buffer>> copy(ObjectOptions objectOptions, String sourcePath, String targetPath);

  Future<ObjectResponse<Buffer>> get(ObjectOptions objectOptions, String path);

  Future<ObjectResponse<Void>> delete(ObjectOptions objectOptions, String path);

  Future<ObjectResponse<Void>> info(ObjectOptions objectOptions, String path);

  Future<ObjectResponse<Buffer>> acl(ObjectOptions objectOptions, String path);

  Future<ObjectResponse<Void>> put(BucketOptions bucketOptions, String path);

  void close();
}
