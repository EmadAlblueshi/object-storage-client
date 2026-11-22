package io.github.emadalblueshi.vertx.s3.impl;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * Test fixture: uploads one part of a multipart upload with a request signed by the shared signer,
 * for tests that need real parts without going through the presigner. Path-style addressing.
 */
public final class MultipartTestSupport {

  private MultipartTestSupport() {
  }

  /** Uploads part {@code partNumber}; the future completes with the part's {@code ETag}. */
  public static Future<String> uploadPart(Vertx vertx, S3TestBackend backend, String bucket, String key, String uploadId,
                                          int partNumber, Buffer body) {
    URI endpoint = backend.endpoint();
    String host = S3SignatureV4.hostHeader(endpoint.getHost(), endpoint.getPort(), backend.tls());
    String path = "/" + bucket + "/" + key;
    List<Map.Entry<String, String>> query = List.of(Map.entry("partNumber", Integer.toString(partNumber)),
      Map.entry("uploadId", uploadId));
    S3SignatureV4.Signed signed = new S3SignatureV4(backend.region(), Clock.systemUTC()).sign(
      new S3SignatureV4.Request("PUT", host, path, query, List.of(), HashUtil.sha256Hex(body.getBytes())),
      backend.accessKey(), backend.secretKey(), null);
    RequestOptions options = new RequestOptions().setMethod(HttpMethod.PUT)
      .setURI(S3SignatureV4.encodePath(path) + "?" + S3SignatureV4.canonicalQuery(query));
    signed.headers.forEach(h -> options.putHeader(h.getKey(), h.getValue()));
    HttpClientAgent http = vertx.createHttpClient(backend.httpClientOptions());
    Promise<String> etag = Promise.promise();
    // The whole exchange is assembled in one task on a Vert.x context
    vertx.getOrCreateContext().runOnContext(v -> http.request(options)
      .compose(request -> request.send(body))
      .compose(response -> response.body().compose(content -> response.statusCode() == 200
        ? Future.succeededFuture(response.getHeader("ETag"))
        : Future.failedFuture("UploadPart failed: HTTP " + response.statusCode() + " " + content)))
      .eventually(http::close)
      .onComplete(etag));
    return etag.future();
  }
}
