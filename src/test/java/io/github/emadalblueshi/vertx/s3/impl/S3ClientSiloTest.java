package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Bucket;
import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/** The client end to end against Silo (shared backends): ListBuckets over HTTP and HTTPS, and server errors. */
@ExtendWith(VertxExtension.class)
class S3ClientSiloTest {

  @Test
  void listBucketsReturnsCreatedBucket(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
    S3TestBackend backend = S3TestBackend.sharedSilo();
    String bucket = SignedRequests.bucketName("list");
    S3Client client = S3Client.create(vertx, options(backend));
    SignedRequests.createBucket(vertx, backend, bucket)
      .compose(v -> client.listBuckets())
      .eventually(client::close)
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        S3Bucket listed = result.getBuckets().stream().filter(b -> b.getName().equals(bucket)).findFirst()
          .orElseThrow(() -> new AssertionError(bucket + " not listed in " + result.getBuckets()));
        assertNotNull(listed.getCreationDate());
        assertTrue(Duration.between(listed.getCreationDate(), Instant.now()).abs().toMinutes() < 10,
          "creation date should be recent: " + listed.getCreationDate());
        assertNotNull(result.getOwner(), "Silo reports an owner");
        testContext.completeNow();
      })));
  }

  @Test
  void listBucketsOverHttps(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
    S3TestBackend backend = S3TestBackend.sharedSiloTls();
    String bucket = SignedRequests.bucketName("tls");
    S3Client client = S3Client.create(vertx, options(backend));
    SignedRequests.createBucket(vertx, backend, bucket)
      .compose(v -> client.listBuckets())
      .eventually(client::close)
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        assertTrue(result.getBuckets().stream().anyMatch(b -> b.getName().equals(bucket)), result.toString());
        testContext.completeNow();
      })));
  }

  @Test
  void wrongSecretGivesSignatureDoesNotMatch(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
    S3TestBackend backend = S3TestBackend.sharedSilo();
    S3Client client = S3Client.create(vertx, options(backend).setCredentials(new S3Credentials(backend.accessKey(), "wrong-secret")));
    client.listBuckets().eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("SignatureDoesNotMatch", service.getErrorCode());
      assertNotNull(service.getRequestId());
      testContext.completeNow();
    })));
  }

  @Test
  void unknownAccessKeyGivesInvalidAccessKeyId(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
    S3TestBackend backend = S3TestBackend.sharedSilo();
    S3Client client = S3Client.create(vertx, options(backend).setCredentials(new S3Credentials("NOSUCHKEY", "secret")));
    client.listBuckets().eventually(client::close).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(403, service.getStatusCode());
      assertEquals("InvalidAccessKeyId", service.getErrorCode());
      testContext.completeNow();
    })));
  }

  static S3ClientOptions options(S3TestBackend backend) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint(backend.endpoint().toString())
      .setRegion(backend.region())
      .setCredentials(new S3Credentials(backend.accessKey(), backend.secretKey()));
    if (backend.tls()) {
      options.getHttpClientOptions().setTrustOptions(backend.httpClientOptions().getTrustOptions());
    }
    return options;
  }
}
