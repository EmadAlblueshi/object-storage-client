package io.github.emadalblueshi.objectstorage.client.s3;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageClient;
import io.github.emadalblueshi.objectstorage.client.s3.impl.S3ClientImpl;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public interface S3Client extends ObjectStorageClient {

  static S3Client create(Vertx vertx, S3ClientOptions s3ClientOptions) {
    return new S3ClientImpl(vertx, s3ClientOptions);
  }

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> put(ObjectOptions objectOptions, String path, Buffer object);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<CopyObjectResult>> copy(ObjectOptions objectOptions, String sourcePath, String targetPath);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Buffer>> get(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> delete(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> info(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<AccessControlPolicy>> acl(ObjectOptions objectOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path,
      CreateBucketConfiguration config);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> deleteBucket(BucketOptions bucketOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<PolicyStatus>> getBucketPolicyStatus(BucketOptions bucketOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> putBucketPolicy(BucketOptions bucketOptions, String path,
      JsonObject policy);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Buffer>> getBucketPolicy(BucketOptions bucketOptions, String path);

  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  Future<S3Response<Void>> deleteBucketPolicy(BucketOptions bucketOptions, String path);
}
