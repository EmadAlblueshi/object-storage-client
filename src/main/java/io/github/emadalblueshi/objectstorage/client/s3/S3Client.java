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

  Future<S3Response<Void>> putObject(S3ObjectOptions s3ObjectOptions, String path, Buffer object);

  Future<S3Response<S3CopyObjectResult>> copyObject(S3ObjectOptions s3ObjectOptions, String sourcePath, String targetPath);

  Future<S3Response<Buffer>> getObject(S3ObjectOptions s3ObjectOptions, String path);

  Future<S3Response<Void>> deleteObject(S3ObjectOptions s3ObjectOptions, String path);

  Future<S3Response<Void>> ObjectInfo(S3ObjectOptions s3ObjectOptions, String path);

  Future<S3Response<S3AccessControlPolicy>> ObjectAcl(S3ObjectOptions s3ObjectOptions, String path);

  Future<S3Response<Void>> putBucket(S3BucketOptions s3BucketOptions, String path);

  Future<S3Response<Void>> putBucket(S3BucketOptions s3BucketOptions, String path,
                                     S3CreateBucketConfiguration s3CreateBucketConfiguration);

  Future<S3Response<Void>> deleteBucket(S3BucketOptions s3BucketOptions, String path);

  Future<S3Response<S3PolicyStatus>> getBucketPolicyStatus(S3BucketOptions s3BucketOptions, String path);

  Future<S3Response<Void>> putBucketPolicy(S3BucketOptions s3BucketOptions, String path,
                                           JsonObject policy);

  Future<S3Response<Buffer>> getBucketPolicy(S3BucketOptions s3BucketOptions, String path);

  Future<S3Response<Void>> deleteBucketPolicy(S3BucketOptions s3BucketOptions, String path);

  Future<S3Response<S3CompleteMultipartUploadResult>> putObjectAsStream(
    S3ObjectOptions s3ObjectOptions, String path, ReadStream<Buffer> readStream);

  Future<S3Response<S3CompleteMultipartUploadResult>> putObjectFileAsStream(
    S3ObjectOptions s3ObjectOptions, String path, String filePath);
}
