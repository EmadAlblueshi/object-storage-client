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

  private final XmlMapper mapper = XmlMapper.builder()
    .addModule(new JavaTimeModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build();
  private final S3ClientOptions clientOptions;
  private final S3AuthOptions authOptions;
  private final WebClient webClient;
  private final Buffer EMPTY_BUFFER = Buffer.buffer("");
  private final Vertx vertx;

  public S3ClientImpl(Vertx vertx, S3ClientOptions s3clientOptions) {
    this.vertx = Objects.requireNonNull(vertx);
    this.clientOptions = Objects.requireNonNull(s3clientOptions);
    this.authOptions = Objects.requireNonNull(s3clientOptions.getAuthOptions());
    this.webClient = WebClient.create(vertx, s3clientOptions.getWebClientOptions());
  }

  @Override
  public Future<S3Response<Void>> putObject(S3ObjectOptions s3ObjectOptions, String objectPath, Buffer object) {
    return request(HttpMethod.PUT, objectPath, object, s3ObjectOptions)
      .sendBuffer(object)
      .compose(toS3Response(Void.class));

  }

  @Override
  public Future<S3Response<S3CopyObjectResult>> copyObject(
    S3ObjectOptions s3ObjectOptions,
    String objectSourcePath,
    String objectTargetPath) {
    s3ObjectOptions.copySource(objectSourcePath);
    return request(HttpMethod.PUT, objectTargetPath, s3ObjectOptions)
      .send()
      .compose(toS3Response(S3CopyObjectResult.class));
  }

  @Override
  public Future<S3Response<Buffer>> getObject(S3ObjectOptions s3ObjectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, s3ObjectOptions)
      .send()
      .compose(toS3Response(Buffer.class));
  }

  @Override
  public Future<S3Response<Void>> deleteObject(S3ObjectOptions s3ObjectOptions, String objectPath) {
    return request(HttpMethod.DELETE, objectPath, s3ObjectOptions)
      .send()
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Void>> ObjectInfo(S3ObjectOptions s3ObjectOptions, String objectPath) {
    return request(HttpMethod.HEAD, objectPath, s3ObjectOptions)
      .send()
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<S3AccessControlPolicy>> ObjectAcl(S3ObjectOptions s3ObjectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, s3ObjectOptions.acl()).send()
      .compose(toS3Response(S3AccessControlPolicy.class));
  }

  @Override
  public Future<S3Response<Void>> putBucket(S3BucketOptions s3BucketOptions, String path,
                                            S3CreateBucketConfiguration s3CreateBucketConfiguration) {
    try {
      Buffer body = Buffer.buffer(mapper.writeValueAsString(s3CreateBucketConfiguration));
      return request(HttpMethod.PUT, path, body, s3BucketOptions)
        .sendBuffer(body)
        .compose(toS3Response(Void.class));
    } catch (JsonProcessingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public Future<S3Response<Void>> putBucket(S3BucketOptions s3BucketOptions, String bucketPath) {
    return request(HttpMethod.PUT, bucketPath, s3BucketOptions)
      .send()
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Void>> deleteBucket(S3BucketOptions s3BucketOptions, String path) {
    return request(HttpMethod.DELETE, path, s3BucketOptions)
      .send()
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<S3PolicyStatus>> getBucketPolicyStatus(S3BucketOptions s3BucketOptions, String path) {
    return request(HttpMethod.GET, path, s3BucketOptions.policyStatus())
      .send()
      .compose(toS3Response(S3PolicyStatus.class));
  }

  @Override
  public Future<S3Response<Void>> putBucketPolicy(S3BucketOptions s3BucketOptions, String path, JsonObject policy) {
    return request(HttpMethod.PUT, path, policy.toBuffer(), s3BucketOptions.policy())
      .sendJsonObject(policy)
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Buffer>> getBucketPolicy(S3BucketOptions s3BucketOptions, String path) {
    return request(HttpMethod.GET, path, s3BucketOptions.policy())
      .send()
      .compose(toS3Response(Buffer.class));
  }

  @Override
  public Future<S3Response<Void>> deleteBucketPolicy(S3BucketOptions s3BucketOptions, String path) {
    return request(HttpMethod.DELETE, path, s3BucketOptions.policy())
      .send()
      .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<S3CompleteMultipartUploadResult>> putObjectAsStream(S3ObjectOptions s3ObjectOptions, String path,
                                                                               ReadStream<Buffer> readStream) {
    return initiateMultipart(path, s3ObjectOptions)
      .compose(initMultipart -> {
        Promise<S3Response<S3CompleteMultipartUploadResult>> promise = Promise.promise();
        String uploadId = initMultipart.body().getUploadId();
        List<S3Part> s3Parts = new ArrayList<>();
        AtomicInteger partNumber = new AtomicInteger(0);
        S3BufferQueueReadStream stream = new S3BufferQueueReadStream(readStream);
        stream.pause();
        stream.handler(buffer -> {
          stream.pause();
          uploadPart(path, buffer, partNumber.incrementAndGet(), uploadId)
            .onComplete(r -> {
              if (r.succeeded()) {
                s3Parts.add(new S3Part(partNumber.get(), r.result().ETag()));
                stream.resume();
              } else {
                promise.fail(r.cause());
              }
            });
        }).exceptionHandler(promise::tryFail).endHandler(e -> {
          completeMultipart(path, uploadId, s3Parts, promise);
        });
        stream.resume();
        return promise.future();
      });
  }

  @Override
  public Future<S3Response<S3CompleteMultipartUploadResult>> putObjectFileAsStream(S3ObjectOptions s3ObjectOptions, String path,
                                                                                   String filePath) {
    return initiateMultipart(path, s3ObjectOptions)
      .compose(initMultipart -> {
        Promise<S3Response<S3CompleteMultipartUploadResult>> promise = Promise.promise();
        String uploadId = initMultipart.body().getUploadId();
        openAsyncFile(filePath).onComplete(f -> {
          if (f.succeeded()) {
            List<S3Part> s3Parts = new ArrayList<>();
            AtomicInteger partNumber = new AtomicInteger(0);
            AsyncFile asyncFile = f.result();
            S3BufferQueueReadStream stream = new S3BufferQueueReadStream(asyncFile);
            stream.pause();
            stream.handler(buffer -> {
              stream.pause();
              uploadPart(path, buffer, partNumber.incrementAndGet(), uploadId)
                .onComplete(r -> {
                  if (r.succeeded()) {
                    s3Parts.add(new S3Part(partNumber.get(), r.result().ETag()));
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
              completeMultipart(path, uploadId, s3Parts, promise);
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

  private Future<S3Response<S3InitiateMultipartUploadResult>> initiateMultipart(String path,
                                                                                S3ObjectOptions s3ObjectOptions) {
    return request(HttpMethod.POST, path, s3ObjectOptions.uploads())
      .send()
      .compose(toS3Response(S3InitiateMultipartUploadResult.class));
  }

  private Future<AsyncFile> openAsyncFile(String filePath) {
    return vertx.fileSystem().open(filePath, new OpenOptions());
  }

  private Future<S3Response<Void>> uploadPart(String path, Buffer buffer, int partNumber, String uploadId) {
    // Upload part and the order must be 'partNumber' then 'uploadId'!
    return request(HttpMethod.PUT, path, buffer,
      new S3ObjectOptions().partNumber(String.valueOf(partNumber)).uploadId(uploadId))
      .sendBuffer(buffer)
      .compose(toS3Response(Void.class));
  }

  private void completeMultipart(String path, String uploadId,
                                 List<S3Part> s3Parts, Promise<S3Response<S3CompleteMultipartUploadResult>> promise) {
    try {
      Buffer body = Buffer.buffer(mapper.writeValueAsString(new S3CompleteMultipartUpload(s3Parts)));
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

  private Future<S3Response<S3CompleteMultipartUploadResult>> completeMultipartUpload(String path, Buffer body,
                                                                                      String uploadId) {
    return request(HttpMethod.POST, path, body, new S3ObjectOptions().uploadId(uploadId))
      .sendBuffer(body)
      .compose(toS3Response(S3CompleteMultipartUploadResult.class));
  }

  private HttpRequest<Buffer> request(
    HttpMethod httpMethod,
    String path,
    Buffer object,
    S3RequestOptions requestOptions) {

    S3AuthOptions authCredentials = authOptions;
    MultiMap headers = requestOptions.headers();
    MultiMap queryParams = requestOptions.queryParams();
    sign(
      clientOptions.getWebClientOptions().getDefaultHost(),
      httpMethod.name(),
      headers,
      path,
      queryParams,
      object.getBytes(),
      authCredentials.getAccessKey(),
      authCredentials.getSecretKey(),
      clientOptions.getRegion(),
      clientOptions.getService());
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
          S3ErrorResponse err = mapper.readValue(httpResponse.bodyAsString(), S3ErrorResponse.class);
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
            body = (clazz != Void.class) ? mapper.<T>readValue(httpResponse.bodyAsString(), clazz) : null;
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
