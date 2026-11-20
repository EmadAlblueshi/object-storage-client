package io.github.emadalblueshi.vertx.s3.impl;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * Test fixtures: raw signed requests against a backend, for setup that the client can't do yet
 * (e.g. putting an object before step 6 adds putObject).
 */
final class SignedRequests {

  private SignedRequests() {
  }

  /** A unique, valid bucket name. */
  static String bucketName(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Puts {@code body} at {@code bucket/key} (path-style) on {@code backend}; fails unless the server answers 200. */
  static Future<Void> putObject(Vertx vertx, S3TestBackend backend, String bucket, String key, Buffer body) {
    URI endpoint = backend.endpoint();
    String path = "/" + bucket + "/" + key;
    S3SignatureV4.Signed signed = new S3SignatureV4(backend.region(), Clock.systemUTC()).sign(
      new S3SignatureV4.Request("PUT", S3SignatureV4.hostHeader(endpoint.getHost(), endpoint.getPort(), backend.tls()),
        path, List.of(), List.of(), HashUtil.sha256Hex(body.getBytes())),
      backend.accessKey(), backend.secretKey(), null);
    RequestOptions options = new RequestOptions().setMethod(HttpMethod.PUT).setURI(S3SignatureV4.encodePath(path));
    signed.headers.forEach(h -> options.addHeader(h.getKey(), h.getValue()));
    options.putHeader("Content-Length", Integer.toString(body.length()));
    HttpClientAgent client = vertx.createHttpClient(backend.httpClientOptions());
    return client.request(options)
      .compose(request -> request.send(body))
      .compose(response -> response.body().compose(responseBody -> response.statusCode() == 200
        ? Future.<Void>succeededFuture()
        : Future.<Void>failedFuture("putObject " + path + ": " + response.statusCode() + " " + responseBody)))
      .eventually(() -> client.close());
  }
}
