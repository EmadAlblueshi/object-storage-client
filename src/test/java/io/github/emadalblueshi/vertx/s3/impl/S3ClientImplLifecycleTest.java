package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Closing the client's {@code Vertx} runs the close hook. Observed through {@link S3ClientImpl#isClosed()},
 * because a closed Vert.x instance can no longer deliver Future callbacks.
 */
@ExtendWith(VertxExtension.class)
class S3ClientImplLifecycleTest {

  @Test
  void closingVertxClosesTheClient(VertxTestContext testContext) {
    Vertx other = Vertx.vertx();
    S3ClientImpl client = (S3ClientImpl) S3Client.create(other, new S3ClientOptions()
      .setEndpoint("http://localhost:1").setRegion("test-west-1").setCredentials(new S3Credentials("a", "b")));
    assertFalse(client.isClosed());
    other.close().onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      assertTrue(client.isClosed(), "the close hook must have closed the client");
      testContext.completeNow();
    })));
  }
}
