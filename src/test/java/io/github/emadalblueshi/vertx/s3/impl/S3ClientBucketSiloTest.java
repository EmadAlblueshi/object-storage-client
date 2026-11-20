package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.junit5.VertxTestContext;

/**
 * The bucket suite against Silo over HTTP, plus checks that create extra buckets (kept off the
 * external backend, where bucket creation is rate-limited).
 */
class S3ClientBucketSiloTest extends S3ClientBucketTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSilo();
  }

  @Test
  void createExistingBucketGivesBucketAlreadyOwnedByYou(VertxTestContext testContext) {
    String bucket = resources.bucket(TestResources.uniqueBucketName());
    client.createBucket(bucket)
      .compose(v -> client.createBucket(bucket))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(409, service.getStatusCode());
        assertEquals("BucketAlreadyOwnedByYou", service.getErrorCode());
        testContext.completeNow();
      })));
  }
}
