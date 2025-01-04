package io.github.emadalblueshi.objectstorage.client.s3.impl;

import static io.github.emadalblueshi.objectstorage.client.s3.impl.S3SignatureV4.sign;

import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.github.emadalblueshi.objectstorage.client.s3.*;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
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

  public S3ClientImpl(Vertx vertx, S3ClientOptions s3clientOptions) {
    Objects.requireNonNull(vertx);
    Objects.requireNonNull(s3clientOptions);
    Objects.requireNonNull(s3clientOptions.getAuthOptions());
    options = s3clientOptions;
    webClient = WebClient.create(vertx, s3clientOptions);
  }

  @Override
  public Future<S3Response<Void>> put(ObjectOptions objectOptions, String objectPath, Buffer object) {
    return request(HttpMethod.PUT, objectPath, object, objectOptions)
        .sendBuffer(object)
        .compose(toS3Response(Void.class));

  }

  @Override
  public Future<S3Response<CopyObjectResult>> copy(ObjectOptions objectOptions, String objectSourcePath,
      String objectTargetPath) {
    objectOptions.copySource(objectSourcePath);
    return request(HttpMethod.PUT, objectTargetPath, objectOptions)
        .send()
        .compose(toS3Response(CopyObjectResult.class));
  }

  @Override
  public Future<S3Response<Buffer>> get(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Buffer.class));
  }

  @Override
  public Future<S3Response<Void>> delete(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.DELETE, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<Void>> info(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.HEAD, objectPath, objectOptions)
        .send()
        .compose(toS3Response(Void.class));
  }

  @Override
  public Future<S3Response<AccessControlPolicy>> acl(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions.acl()).send()
        .compose(toS3Response(AccessControlPolicy.class));
  }

  @Override
  public Future<S3Response<Void>> putBucket(BucketOptions bucketOptions, String path,
      CreateBucketConfiguration config) {
    String bucket = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<CreateBucketConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        // + "<LocationConstraint>default</LocationConstraint>"
        // + "<Location><Name>string</Name><Type>string</Type></Location>"
        // "<Bucket><DataRedundancy>SingleAvailabilityZone</DataRedundancy><Type>string</Type></Bucket>"
        + "</CreateBucketConfiguration>";
    Buffer body = Buffer.buffer(bucket);
    return request(HttpMethod.PUT, path, body, bucketOptions)
        .sendBuffer(body)
        .compose(toS3Response(Void.class));
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
  public void close() {
    webClient.close();
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
