package io.github.emadalblueshi.objectstorage.client.s3;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageClient;
import io.github.emadalblueshi.objectstorage.client.s3.impl.S3ClientImpl;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@VertxGen
public interface S3Client extends ObjectStorageClient {

  static S3Client create(Vertx vertx, S3ClientOptions s3ClientOptions) {
    return new S3ClientImpl(vertx, s3ClientOptions);
  }

  Future<S3Response<Void>> putObject(ObjectOptions objectOptions, String path, Buffer object);

  Future<S3Response<CopyObjectResult>> copyObject(ObjectOptions objectOptions, String sourcePath, String targetPath);

  Future<S3Response<Buffer>> getObject(ObjectOptions objectOptions, String path);

  Future<S3Response<Void>> deleteObject(ObjectOptions objectOptions, String path);

  Future<S3Response<Void>> ObjectInfo(ObjectOptions objectOptions, String path);

  Future<S3Response<AccessControlPolicy>> ObjectAcl(ObjectOptions objectOptions, String path);

  Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path);

  Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path,
      CreateBucketConfiguration createBucketConfiguration);

  Future<S3Response<Void>> deleteBucket(BucketOptions bucketOptions, String path);

  Future<S3Response<PolicyStatus>> getBucketPolicyStatus(BucketOptions bucketOptions, String path);

  Future<S3Response<Void>> putBucketPolicy(BucketOptions bucketOptions, String path,
      JsonObject policy);

  Future<S3Response<Buffer>> getBucketPolicy(BucketOptions bucketOptions, String path);

  Future<S3Response<Void>> deleteBucketPolicy(BucketOptions bucketOptions, String path);

  Future<S3Response<CompleteMultipartUploadResult>> putObjectAsStream(
    ObjectOptions objectOptions, String path, ReadStream<Buffer> readStream);

  Future<S3Response<CompleteMultipartUploadResult>> putObjectFileAsStream(
    ObjectOptions objectOptions, String path, String filePath);
}
