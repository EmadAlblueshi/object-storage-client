package io.github.emadalblueshi.vertx.s3.impl;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * Test fixtures: raw signed requests against a backend, for setup that the client can't do yet
 * (e.g. creating buckets before step 5 adds createBucket).
 */
final class SignedRequests {

  private SignedRequests() {
  }

  /** A unique, valid bucket name. */
  static String bucketName(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Creates {@code bucket} on {@code backend}; fails unless the server answers 200. */
  static Future<Void> createBucket(Vertx vertx, S3TestBackend backend, String bucket) {
    URI endpoint = backend.endpoint();
    String path = "/" + bucket;
    S3SignatureV4.Signed signed = new S3SignatureV4(backend.region(), Clock.systemUTC()).sign(
      new S3SignatureV4.Request("PUT", S3SignatureV4.hostHeader(endpoint.getHost(), endpoint.getPort(), backend.tls()),
        path, List.of(), List.of(), S3SignatureV4.EMPTY_PAYLOAD_SHA256),
      backend.accessKey(), backend.secretKey(), null);
    RequestOptions options = new RequestOptions().setMethod(HttpMethod.PUT).setURI(S3SignatureV4.encodePath(path));
    signed.headers.forEach(h -> options.addHeader(h.getKey(), h.getValue()));
    HttpClientAgent client = vertx.createHttpClient(backend.httpClientOptions());
    return client.request(options)
      .compose(request -> request.send())
      .compose(response -> response.body().compose(body -> response.statusCode() == 200
        ? Future.<Void>succeededFuture()
        : Future.<Void>failedFuture("createBucket " + bucket + ": " + response.statusCode() + " " + body)))
      .eventually(() -> client.close());
  }
}
