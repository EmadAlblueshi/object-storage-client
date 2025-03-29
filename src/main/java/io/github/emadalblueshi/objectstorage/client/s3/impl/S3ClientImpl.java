package io.github.emadalblueshi.objectstorage.client.s3.impl;

import static io.github.emadalblueshi.objectstorage.client.s3.impl.S3SignatureV4.sign;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.github.emadalblueshi.objectstorage.client.s3.*;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class S3ClientImpl implements S3Client {

  private final XmlMapper xmlMapper = XmlMapper.builder()
      .addModule(new JavaTimeModule())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .build();
  private final S3ClientOptions options;
  private final WebClient webClient;
  private final Buffer EMPTY_BUFFER = Buffer.buffer("");
  private final Vertx vertx;

  public S3ClientImpl(Vertx vertx, S3ClientOptions s3clientOptions) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(s3clientOptions.getAuthOptions());
    this.vertx = vertx;
    options = Objects.requireNonNull(s3clientOptions);
    webClient = WebClient.create(vertx, s3clientOptions);
  }

  @Override
  public Future<S3Response<Void>> putObject(ObjectOptions objectOptions, String objectPath, Buffer object) {
    return request(HttpMethod.PUT, objectPath, object, objectOptions)
        .sendBuffer(object)
        .compose(toS3Response(Void.class));

  }

  @Override
  public Future<S3Response<CopyObjectResult>> copyObject(
    ObjectOptions objectOptions,
    String objectSourcePath,
    String objectTargetPath) {
    objectOptions.copySource(objectSourcePath);
    return request(HttpMethod.PUT, objectTargetPath, objectOptions)
        .send()
        .compose(toS3Response(CopyObjectResult.class));
  }

  @Override
  public Future<S3Response<Buffer>> getObject(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Buffer.class));
  }

  @Override
  public Future<S3Response<Void>> deleteObject(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.DELETE, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Void>> ObjectInfo(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.HEAD, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<AccessControlPolicy>> ObjectAcl(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions.acl()).send()
        .compose(toS3Response(AccessControlPolicy.class));
  }

  @Override
  public Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path,
      CreateBucketConfiguration createBucketConfiguration)  {
    try {
      Buffer body = Buffer.buffer(xmlMapper.writeValueAsString(createBucketConfiguration));
      return request(HttpMethod.PUT, path, body, bucketOptions)
        .sendBuffer(body)
        .compose(toS3Response(Void.class));
    } catch (JsonProcessingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String bucketPath) {
    return request(HttpMethod.PUT, bucketPath, bucketOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Void>> deleteBucket(BucketOptions bucketOptions, String path) {
    return request(HttpMethod.DELETE, path, bucketOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<PolicyStatus>> getBucketPolicyStatus(BucketOptions bucketOptions, String path) {
    return request(HttpMethod.GET, path, bucketOptions.policyStatus())
        .send()
        .compose(toS3Response(PolicyStatus.class));
  }

  @Override
  public Future<S3Response<Void>> putBucketPolicy(BucketOptions bucketOptions, String path, JsonObject policy) {
    return request(HttpMethod.PUT, path, policy.toBuffer(), bucketOptions.policy())
        .sendJsonObject(policy)
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Buffer>> getBucketPolicy(BucketOptions bucketOptions, String path) {
    return request(HttpMethod.GET, path, bucketOptions.policy())
        .send()
        .compose(toS3Response(Buffer.class));
  }

  @Override
  public Future<S3Response<Void>> deleteBucketPolicy(BucketOptions bucketOptions, String path) {
    return request(HttpMethod.DELETE, path, bucketOptions.policy())
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<CompleteMultipartUploadResult>> putObjectAsStream(ObjectOptions objectOptions, String path,
                                                                             ReadStream<Buffer> readStream) {
    return initiateMultipart(path, objectOptions)
        .compose(initMultipart -> {
          Promise<S3Response<CompleteMultipartUploadResult>> promise = Promise.promise();
          String uploadId = initMultipart.body().getUploadId();
          List<Part> parts = new ArrayList<>();
          AtomicInteger partNumber = new AtomicInteger(0);
          BufferQueueReadStream stream = new BufferQueueReadStream(readStream);
          stream.handler(buffer -> {
            stream.pause();
            uploadPart(path, buffer, partNumber.incrementAndGet(), uploadId)
                .onComplete(r -> {
                  if (r.succeeded()) {
                    parts.add(new Part(partNumber.get(), r.result().ETag()));
                    stream.resume();
                  } else {
                    promise.fail(r.cause());
                  }
                });
          }).exceptionHandler(promise::tryFail).endHandler(e -> {
            completeMultipart(path, uploadId, parts, promise);
          });
          stream.resume();
          return promise.future();
        });
  }

  @Override
  public Future<S3Response<CompleteMultipartUploadResult>> putObjectFileAsStream(ObjectOptions objectOptions, String path,
                                                                                 String filePath) {
    return initiateMultipart(path, objectOptions)
        .compose(initMultipart -> {
          Promise<S3Response<CompleteMultipartUploadResult>> promise = Promise.promise();
          String uploadId = initMultipart.body().getUploadId();
          openAsyncFile(filePath).onComplete(f -> {
            if (f.succeeded()) {
              List<Part> parts = new ArrayList<>();
              AtomicInteger partNumber = new AtomicInteger(0);
              AsyncFile asyncFile = f.result();
              BufferQueueReadStream stream = new BufferQueueReadStream(asyncFile);
              stream.handler(buffer -> {
                stream.pause();
                uploadPart(path, buffer, partNumber.incrementAndGet(), uploadId)
                    .onComplete(r -> {
                      if (r.succeeded()) {
                        parts.add(new Part(partNumber.get(), r.result().ETag()));
                        stream.resume();
                      } else {
                        promise.fail(r.cause());
                        asyncFile.close();
                      }
                    });
              }).exceptionHandler(e -> {
                promise.tryFail(e);
                asyncFile.close();
              }).endHandler(e -> {
                completeMultipart(path, uploadId, parts, promise);
              });
              stream.resume();
            } else {
              promise.fail(f.cause());
            }
          });
          return promise.future();
        });
  }

  @Override
  public void close() {
    webClient.close();
  }

  private Future<S3Response<InitiateMultipartUploadResult>> initiateMultipart(String path,
      ObjectOptions objectOptions) {
    return request(HttpMethod.POST, path, objectOptions.uploads())
        .send()
        .compose(toS3Response(InitiateMultipartUploadResult.class));
  }

  private Future<AsyncFile> openAsyncFile(String filePath) {
    return vertx.fileSystem().open(filePath, new OpenOptions());
  }

  private Future<S3Response<Void>> uploadPart(String path, Buffer buffer, int partNumber, String uploadId) {
    // Upload part and the order must be 'partNumber' then 'uploadId'!
    return request(HttpMethod.PUT, path, buffer,
        new ObjectOptions().partNumber(String.valueOf(partNumber)).uploadId(uploadId))
        .sendBuffer(buffer)
        .compose(toS3Response(Void.class));
  }

  private void completeMultipart(String path, String uploadId,
      List<Part> parts, Promise<S3Response<CompleteMultipartUploadResult>> promise) {
    try {
      Buffer body = Buffer.buffer(xmlMapper.writeValueAsString(new CompleteMultipartUpload(parts)));
      completeMultipartUpload(path, body, uploadId).onComplete(r -> {
        if (r.succeeded()) {
          promise.tryComplete(r.result());
        } else {
          promise.tryFail(r.cause());
        }
      });
    } catch (JsonProcessingException ex) {
      promise.tryFail(ex);
    }
  }

  private Future<S3Response<CompleteMultipartUploadResult>> completeMultipartUpload(String path, Buffer body,
      String uploadId) {
    return request(HttpMethod.POST, path, body, new ObjectOptions().uploadId(uploadId))
        .sendBuffer(body)
        .compose(toS3Response(CompleteMultipartUploadResult.class));
  }

  private HttpRequest<Buffer> request(
      HttpMethod httpMethod,
      String path,
      Buffer object,
      S3RequestOptions requestOptions) {

    S3AuthOptions authCredentials = options.getAuthOptions();
    MultiMap headers = requestOptions.headers();
    MultiMap queryParams = requestOptions.queryParams();
    sign(
        options.getDefaultHost(),
        httpMethod.name(),
        headers,
        path,
        queryParams,
        object.getBytes(),
        authCredentials.getAccessKey(),
        authCredentials.getSecretKey(),
        options.getRegion(),
        options.getService());
    HttpRequest<Buffer> httpRequest = webClient.request(httpMethod, path);
    httpRequest.headers().addAll(headers);
    httpRequest.queryParams().addAll(queryParams);
    return httpRequest;

  }

  private HttpRequest<Buffer> request(
      HttpMethod httpMethod,
      String path,
      S3RequestOptions requestOptions) {
    return request(httpMethod, path, EMPTY_BUFFER, requestOptions);

  }

  @SuppressWarnings("unchecked")
  private <T> Function<HttpResponse<Buffer>, Future<S3Response<T>>> toS3Response(Class<T> clazz) {
    return httpResponse -> {
      Promise<S3Response<T>> promise = Promise.promise();
      if (httpResponse.statusCode() > 300 && httpResponse.statusCode() < 600) {
        try {
          S3ErrorResponse err = xmlMapper.readValue(httpResponse.bodyAsString(), S3ErrorResponse.class);
          promise.fail(new S3ResponseException(
              err.getCode(),
              err.getMessage(),
              err.getResource(),
              err.getRequestId()));
        } catch (Throwable e) {
          promise.fail(e);
        }
      } else {
        try {
          T body = null;
          if (!clazz.equals(Buffer.class)) {
            body = (clazz != Void.class) ? xmlMapper.<T>readValue(httpResponse.bodyAsString(), clazz) : null;
          } else {
            body = (T) httpResponse.bodyAsBuffer();
          }
          promise.complete(new S3ResponseImpl<>(
              httpResponse.version(),
              httpResponse.statusCode(),
              httpResponse.statusMessage(),
              httpResponse.headers(),
              httpResponse.trailers(),
              httpResponse.cookies(),
              body,
              httpResponse.followedRedirects()));
        } catch (Throwable e) {
          promise.fail(e);
        }
      }
      return promise.future();
    };

  }
}
