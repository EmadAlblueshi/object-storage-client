package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Bucket;
import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Bucket operations (including listing an empty bucket) and authentication errors end to end, against one {@link S3TestBackend} per
 * subclass (Silo over HTTP and HTTPS, and the opt-in external backend).
 * <p>
 * Safe on a real account: exactly one bucket is created (with the client's region as its
 * location constraint), uniquely named, and deleted in cleanup even if the test fails. The other
 * tests create nothing.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientBucketTestBase {

  protected Vertx vertx;
  protected S3TestBackend backend;
  protected S3Client client;
  protected final TestResources resources = new TestResources();

  /** The backend for this suite; may abort the class (skipped) if it isn't available. */
  protected abstract S3TestBackend backend();

  @BeforeAll
  void setUp(Vertx vertx) {
    this.vertx = vertx;
    this.backend = backend();
    this.client = S3Client.create(vertx, TestResources.clientOptions(backend));
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    if (client == null) {
      testContext.completeNow();
      return;
    }
    resources.cleanUp(client).eventually(client::close).onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void bucketLifecycle(VertxTestContext testContext) {
    String bucket = resources.bucket(TestResources.uniqueBucketName());
    client.createBucket(bucket)
      .compose(v -> client.headBucket(bucket))
      .compose(v -> client.listObjects(bucket, null))
      .compose(empty -> {
        testContext.verify(() -> {
          assertTrue(empty.getObjects().isEmpty(), "a new bucket is empty: " + empty);
          assertTrue(empty.getCommonPrefixes().isEmpty());
          assertFalse(empty.isTruncated());
          assertNull(empty.getNextContinuationToken());
        });
        return client.listBuckets();
      })
      .compose(result -> {
        testContext.verify(() -> {
          S3Bucket listed = result.getBuckets().stream().filter(b -> b.getName().equals(bucket)).findFirst()
            .orElseThrow(() -> new AssertionError(bucket + " not listed"));
          assertNotNull(listed.getCreationDate());
          assertTrue(Duration.between(listed.getCreationDate(), Instant.now()).abs().toMinutes() < 10,
            "creation date should be recent: " + listed.getCreationDate());
          assertNotNull(result.getOwner(), "an owner is reported");
        });
        return client.deleteBucket(bucket);
      })
      .compose(v -> client.headBucket(bucket))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(404, service.getStatusCode(), service.getMessage());
        assertNull(service.getErrorCode(), "HEAD responses have no error document");
        testContext.completeNow();
      })));
  }

  @Test
  void deleteMissingBucketGivesNoSuchBucket(VertxTestContext testContext) {
    client.deleteBucket(TestResources.uniqueBucketName()).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(404, service.getStatusCode());
      assertEquals("NoSuchBucket", service.getErrorCode());
      assertNotNull(service.getRequestId());
      testContext.completeNow();
    })));
  }

  @Test
  void headMissingBucketIs404WithoutErrorCode(VertxTestContext testContext) {
    client.headBucket(TestResources.uniqueBucketName()).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(404, service.getStatusCode());
      assertNull(service.getErrorCode());
      testContext.completeNow();
    })));
  }

  @Test
  void wrongSecretGivesSignatureDoesNotMatch(VertxTestContext testContext) {
    S3Client wrong = S3Client.create(vertx, TestResources.clientOptions(backend)
      .setCredentials(new S3Credentials(backend.accessKey(), "wrong-secret")));
    wrong.listBuckets().eventually(wrong::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("SignatureDoesNotMatch", service.getErrorCode());
      testContext.completeNow();
    })));
  }

  @Test
  void unknownAccessKeyGivesInvalidAccessKeyId(VertxTestContext testContext) {
    S3Client unknown = S3Client.create(vertx, TestResources.clientOptions(backend)
      .setCredentials(new S3Credentials("NOSUCHACCESSKEY0000", "secret")));
    unknown.listBuckets().eventually(unknown::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("InvalidAccessKeyId", service.getErrorCode());
      testContext.completeNow();
    })));
  }
}
