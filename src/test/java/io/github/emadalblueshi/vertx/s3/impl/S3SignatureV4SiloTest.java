package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * The signer against a real S3-compatible server (Silo), which verifies every signature
 * server-side. Covers the region-enforcement smoke test deferred from Phase 1 step 4, plus the
 * S3-specific rules the generic AWS suite can't cover: single-encoded paths with special
 * characters (including non-BMP), and sorted, encoded multi-parameter queries.
 * <p>
 * Requests go through a plain Vert.x {@code HttpClient}. The wire path and query are built with
 * the same helpers the signer uses, so what is sent is exactly what is signed.
 */
@ExtendWith(VertxExtension.class)
class S3SignatureV4SiloTest {

  private static final S3TestBackend BACKEND = S3TestBackend.silo();

  @BeforeAll
  static void startBackend() {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  void requestSignedForAnotherRegionIsRejected(Vertx vertx, VertxTestContext testContext) {
    String bucket = "/" + bucketName("region");
    List<Map.Entry<String, String>> list = List.of(Map.entry("list-type", "2"));
    send(vertx, BACKEND.region(), HttpMethod.PUT, bucket, List.of(), null)
      .compose(created -> {
        assertEquals(200, created.status, created.body);
        return send(vertx, "us-east-1", HttpMethod.GET, bucket, list, null);
      })
      .compose(wrongRegion -> {
        assertEquals(400, wrongRegion.status, wrongRegion.body);
        assertTrue(wrongRegion.body.contains("<Code>AuthorizationHeaderMalformed</Code>"), wrongRegion.body);
        return send(vertx, BACKEND.region(), HttpMethod.GET, bucket, list, null);
      })
      .onComplete(testContext.succeeding(rightRegion -> testContext.verify(() -> {
        assertEquals(200, rightRegion.status, rightRegion.body);
        testContext.completeNow();
      })));
  }

  @Test
  void specialCharacterKeysRoundTrip(Vertx vertx, VertxTestContext testContext) {
    String bucket = "/" + bucketName("keys");
    // Keys with empty, "." or ".." segments ("a//b", "a/./b") are valid on AWS, but MinIO-compatible
    // servers reject them (XMinioInvalidObjectName / XMinioInvalidResourceName). That the signer
    // doesn't normalise them is unit-tested in S3SignatureV4Test.
    List<String> keys = List.of("dir/a b+c%~ü.txt", "*()'!@$&=;:,?#[]", "emoji-\uD83D\uDE80/x y.bin");
    Future<Response> chain = send(vertx, BACKEND.region(), HttpMethod.PUT, bucket, List.of(), null)
      .map(r -> expectStatus(r, 200));
    for (String key : keys) {
      String path = bucket + "/" + key;
      Buffer content = Buffer.buffer("content of " + key);
      chain = chain
        .compose(r -> send(vertx, BACKEND.region(), HttpMethod.PUT, path, List.of(), content))
        .map(r -> expectStatus(r, 200))
        .compose(r -> send(vertx, BACKEND.region(), HttpMethod.GET, path, List.of(), null))
        .map(r -> {
          expectStatus(r, 200);
          assertEquals(content.toString(), r.body, "content of " + key);
          return r;
        });
    }
    chain.onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void unsortedMultiParameterQueryIsAccepted(Vertx vertx, VertxTestContext testContext) {
    String bucket = "/" + bucketName("query");
    // Deliberately unsorted, with values needing encoding; df34dc8 signed them in insertion order.
    List<Map.Entry<String, String>> query = List.of(
      Map.entry("prefix", "dir/a b"), Map.entry("max-keys", "10"),
      Map.entry("list-type", "2"), Map.entry("encoding-type", "url"));
    send(vertx, BACKEND.region(), HttpMethod.PUT, bucket, List.of(), null)
      .map(r -> expectStatus(r, 200))
      .compose(r -> send(vertx, BACKEND.region(), HttpMethod.PUT, bucket + "/dir/a b.txt", List.of(), Buffer.buffer("x")))
      .map(r -> expectStatus(r, 200))
      .compose(r -> send(vertx, BACKEND.region(), HttpMethod.GET, bucket, query, null))
      .onComplete(testContext.succeeding(listed -> testContext.verify(() -> {
        expectStatus(listed, 200);
        // encoding-type=url encodes keys form-style: a space comes back as '+'.
        assertTrue(listed.body.contains("<Key>dir/a+b.txt</Key>"), listed.body);
        testContext.completeNow();
      })));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Future<Response> send(Vertx vertx, String region, HttpMethod method, String path,
                                       List<Map.Entry<String, String>> query, Buffer body) {
    URI endpoint = BACKEND.endpoint();
    String host = S3SignatureV4.hostHeader(endpoint.getHost(), endpoint.getPort(), BACKEND.tls());
    String payloadHash = body == null ? S3SignatureV4.EMPTY_PAYLOAD_SHA256 : HashUtil.sha256Hex(body.getBytes());
    S3SignatureV4.Signed signed = new S3SignatureV4(region, Clock.systemUTC()).sign(
      new S3SignatureV4.Request(method.name(), host, path, query, List.of(), payloadHash),
      BACKEND.accessKey(), BACKEND.secretKey(), null);

    String uri = S3SignatureV4.encodePath(path) + (query.isEmpty() ? "" : "?" + S3SignatureV4.canonicalQuery(query));
    RequestOptions options = new RequestOptions().setMethod(method).setURI(uri);
    signed.headers.forEach(h -> options.addHeader(h.getKey(), h.getValue()));

    HttpClientAgent client = vertx.createHttpClient(BACKEND.httpClientOptions());
    return client.request(options)
      .compose(request -> body == null ? request.send() : request.send(body))
      .compose(response -> response.body().map(b -> new Response(response.statusCode(), b.toString())))
      .eventually(() -> client.close());
  }

  private static Response expectStatus(Response response, int status) {
    assertEquals(status, response.status, response.body);
    return response;
  }

  private static String bucketName(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }
}
