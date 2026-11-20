package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.context.CallerContext;
import io.github.emadalblueshi.vertx.s3.context.CallerKind;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Context correctness of every public {@link S3Client} method, against Silo: successes and
 * failures must complete on the caller's context, for each {@link CallerKind} (one subclass
 * each; the virtual-thread one is tagged {@code virtual-threads}).
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientContextTestBase {

  protected Vertx vertx;
  protected CallerContext caller;
  protected S3TestBackend backend;
  protected S3Client client;

  protected abstract CallerKind kind();

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = S3TestBackend.sharedSilo();
    this.client = S3Client.create(vertx, options(backend));
    CallerContext.deploy(vertx, kind()).onComplete(testContext.succeeding(c -> {
      caller = c;
      testContext.completeNow();
    }));
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    caller.undeploy().compose(v -> client.close()).onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void listBucketsCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.listBuckets())
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        assertNotNull(result.getBuckets());
        testContext.completeNow();
      })));
  }

  @Test
  void listBucketsFailureCompletesOnCallerContext(VertxTestContext testContext) {
    S3Client wrongSecret = S3Client.create(vertx,
      options(backend).setCredentials(new S3Credentials(backend.accessKey(), "wrong-secret")));
    caller.assertCompletesOnCallerContext(ctx -> wrongSecret.listBuckets())
      .eventually(wrongSecret::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("SignatureDoesNotMatch", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void closeCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> S3Client.create(vertx, options(backend)).close())
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void operationOnClosedClientFailsOnCallerContext(VertxTestContext testContext) {
    S3Client closed = S3Client.create(vertx, options(backend));
    closed.close()
      .compose(v -> caller.assertCompletesOnCallerContext(ctx -> closed.listBuckets()))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
        testContext.completeNow();
      })));
  }

  static S3ClientOptions options(S3TestBackend backend) {
    return new S3ClientOptions()
      .setEndpoint(backend.endpoint().toString())
      .setRegion(backend.region())
      .setCredentials(new S3Credentials(backend.accessKey(), backend.secretKey()));
  }
}
