package io.github.emadalblueshi.objectstorage.client.impl;

import static io.github.emadalblueshi.objectstorage.client.util.S3SignatureV4.sign;

import java.util.function.Function;

import io.github.emadalblueshi.objectstorage.client.*;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectStorageClientImpl implements ObjectStorageClient {

  private final ObjectStorageClientOptions options;
  private final WebClient client;
  private final Buffer EMPTY = Buffer.buffer("");

  private <T> Function<HttpResponse<T>, ObjectResponse<T>> objectResponse() {
    return httpResponse -> new ObjectResponseImpl<T>(
        httpResponse.version(),
        httpResponse.statusCode(),
        httpResponse.statusMessage(),
        httpResponse.headers(),
        httpResponse.trailers(),
        httpResponse.cookies(),
        httpResponse.body(),
        httpResponse.followedRedirects());
  }

  public ObjectStorageClientImpl(Vertx vertx, ObjectStorageClientOptions objectStorageClientOptions) {
    options = objectStorageClientOptions;
    client = WebClient.create(vertx, objectStorageClientOptions);
  }

  @Override
  public Future<ObjectResponse<Void>> put(ObjectOptions objectOptions, String objectPath, Buffer object) {
    return request(HttpMethod.PUT, objectPath, object, objectOptions)
        .as(BodyCodec.none())
        .sendBuffer(object)
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Buffer>> copy(ObjectOptions objectOptions, String objectSourcePath,
      String objectTargetPath) {
    objectOptions.copySource(objectSourcePath);
    return request(HttpMethod.PUT, objectTargetPath, objectOptions)
        .send()
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Buffer>> get(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions)
        .send()
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Void>> delete(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.DELETE, objectPath, objectOptions)
        .as(BodyCodec.none())
        .send()
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Void>> info(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.HEAD, objectPath, objectOptions)
        .as(BodyCodec.none())
        .send()
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Buffer>> acl(ObjectOptions objectOptions, String objectPath) {
    return request(HttpMethod.GET, objectPath, objectOptions.acl())
        .send()
        .map(objectResponse());
  }

  @Override
  public Future<ObjectResponse<Void>> put(BucketOptions bucketOptions, String bucketPath) {
    return request(HttpMethod.PUT, bucketPath, bucketOptions)
        .as(BodyCodec.none())
        .send()
        .map(objectResponse());
  }

  @Override
  public void close() {
    client.close();
  }

  private HttpRequest<Buffer> request(
      HttpMethod httpMethod,
      String path,
      Buffer object,
      ObjectStorageRequestOptions requestOptions) {

    S3Options s3Options = options.getS3Options();
    MultiMap headers = requestOptions.headers();
    MultiMap queryParams = requestOptions.queryParams();
    sign(
        options.getDefaultHost(),
        httpMethod.name(),
        headers,
        path,
        queryParams,
        object.getBytes(),
        s3Options.getAccessKey(),
        s3Options.getSecretKey(),
        s3Options.getRegion(),
        s3Options.getService());
    HttpRequest<Buffer> httpRequest = client.request(httpMethod, path);
    httpRequest.headers().addAll(headers);
    httpRequest.queryParams().addAll(queryParams);
    return httpRequest;

  }

  private HttpRequest<Buffer> request(
      HttpMethod httpMethod,
      String path,
      ObjectStorageRequestOptions requestOptions) {
    return request(httpMethod, path, EMPTY, requestOptions);

  }

}
