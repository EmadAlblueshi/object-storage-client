package io.github.emadalblueshi.objectstorage.client.s3;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageClient;
import io.github.emadalblueshi.objectstorage.client.s3.impl.S3ClientImpl;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public interface S3Client extends ObjectStorageClient {

  static S3Client create(Vertx vertx, S3ClientOptions s3ClientOptions) {
    return new S3ClientImpl(vertx, s3ClientOptions);
  }

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Void>> put(ObjectOptions objectOptions, String path, Buffer object);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Buffer>> copy(ObjectOptions objectOptions, String sourcePath, String targetPath);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Buffer>> get(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Void>> delete(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Void>> info(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Buffer>> acl(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<ObjectResponse<Void>> put(BucketOptions bucketOptions, String path);

}
