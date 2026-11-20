package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/**
 * Where the payload hash is computed. Deterministic: the Vert.x instance has a single worker
 * thread, held busy by the test, so a hash that is offloaded via {@code executeBlocking} cannot
 * complete until the test releases it, while an inline hash is complete on return.
 */
class PayloadHashTest {

  private Vertx vertx;
  private Transport http;
  private Transport https;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
    http = new Transport(vertx, ClientConfig.from(options("http://localhost:1")));
    https = new Transport(vertx, ClientConfig.from(options("https://localhost:1")));
  }

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().await(10, TimeUnit.SECONDS);
  }

  @Test
  void largeBodyOverHttpIsHashedOnAWorkerNotInline() throws Exception {
    byte[] data = new byte[PayloadMode.MAX_INLINE_HASH_SIZE + 1];
    new Random(7).nextBytes(data);
    CountDownLatch workerBusy = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    vertx.executeBlocking(() -> {
      workerBusy.countDown();
      return release.await(10, TimeUnit.SECONDS);
    }, false);
    assertTrue(workerBusy.await(10, TimeUnit.SECONDS));

    Future<String> hash = http.payloadHash(ContextSupport.capture(vertx), put(data));
    assertFalse(hash.isComplete(), "the only worker is busy, so an offloaded hash can't be done yet");

    release.countDown();
    assertEquals(HashUtil.sha256Hex(data), hash.await(10, TimeUnit.SECONDS));
  }

  @Test
  void smallBodyOverHttpIsHashedInline() {
    Future<String> hash = http.payloadHash(ContextSupport.capture(vertx), put("small".getBytes()));
    assertTrue(hash.isComplete());
    assertEquals(HashUtil.sha256Hex("small"), hash.result());
  }

  @Test
  void largeBodyOverHttpsIsNotHashed() {
    Future<String> hash = https.payloadHash(ContextSupport.capture(vertx), put(new byte[PayloadMode.MAX_INLINE_HASH_SIZE + 1]));
    assertTrue(hash.isComplete());
    assertEquals(S3SignatureV4.UNSIGNED_PAYLOAD, hash.result());
  }

  private static S3Request put(byte[] data) {
    return new S3Request(HttpMethod.PUT, "bucket", "key", List.of(), List.of(), Buffer.buffer(data));
  }

  private static S3ClientOptions options(String endpoint) {
    return new S3ClientOptions().setEndpoint(endpoint).setRegion("test-west-1")
      .setCredentials(new S3Credentials("access", "secret"));
  }
}
