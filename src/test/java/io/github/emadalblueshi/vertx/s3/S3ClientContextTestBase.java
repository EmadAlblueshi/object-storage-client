package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.context.CallerContext;
import io.github.emadalblueshi.vertx.s3.context.CallerKind;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
  private final String objectBucket = "ctx-objects-" + UUID.randomUUID().toString().substring(0, 8);
  private final byte[] streamContent = new byte[256 * 1024];

  protected abstract CallerKind kind();

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = S3TestBackend.sharedSilo();
    this.client = S3Client.create(vertx, options(backend));
    new java.util.Random(1).nextBytes(streamContent);
    // Fixtures are created outside the caller; only the operations under test run as the caller
    client.createBucket(objectBucket)
      .compose(v -> client.putObject(objectBucket, "stream-object", Buffer.buffer(streamContent)))
      .compose(etag -> client.putObject(objectBucket, "empty-object", Buffer.buffer()))
      .compose(etag -> CallerContext.deploy(vertx, kind()))
      .onComplete(testContext.succeeding(c -> {
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
  void bucketOperationsCompleteOnCallerContext(VertxTestContext testContext) {
    String bucket = "ctx-" + UUID.randomUUID().toString().substring(0, 8);
    caller.assertCompletesOnCallerContext(ctx -> client.createBucket(bucket))
      .compose(v -> caller.assertCompletesOnCallerContext(ctx -> client.headBucket(bucket)))
      .compose(v -> caller.assertCompletesOnCallerContext(ctx -> client.deleteBucket(bucket)))
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void bucketServerFailureCompletesOnCallerContext(VertxTestContext testContext) {
    String missing = "ctx-missing-" + UUID.randomUUID().toString().substring(0, 8);
    caller.assertCompletesOnCallerContext(ctx -> client.deleteBucket(missing))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchBucket", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void invalidBucketNameFailsOnCallerContext(VertxTestContext testContext) {
    // Fails before any I/O: the immediate-failure path must still complete on the caller's context
    caller.assertCompletesOnCallerContext(ctx -> client.headBucket("Not_Valid"))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertTrue(e.getMessage().startsWith("Invalid bucket name"), e.getMessage());
        testContext.completeNow();
      })));
  }

  @Test
  void objectOperationsCompleteOnCallerContext(VertxTestContext testContext) {
    String key = "ops-" + kind();
    caller.assertCompletesOnCallerContext(ctx -> client.putObject(objectBucket, key, Buffer.buffer("data"),
        new S3PutObjectOptions().setContentType("text/plain")))
      .compose(etag -> caller.assertCompletesOnCallerContext(ctx -> client.headObject(objectBucket, key)))
      .compose(head -> caller.assertCompletesOnCallerContext(ctx -> client.getObject(objectBucket, key)))
      .compose(body -> {
        testContext.verify(() -> assertEquals("data", body.toString()));
        return caller.assertCompletesOnCallerContext(ctx -> client.deleteObject(objectBucket, key));
      })
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void listObjectsCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.listObjects(objectBucket, new S3ListObjectsOptions().setPrefix("stream-")))
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        assertEquals("stream-object", result.getObjects().get(0).getKey());
        testContext.completeNow();
      })));
  }

  @Test
  void listObjectsFailureCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.listObjects("ctx-missing-" + UUID.randomUUID().toString().substring(0, 8), null))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchBucket", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void getObjectFailureCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.getObject(objectBucket, "no-such-key"))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchKey", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void getObjectStreamDeliversOnCallerContextWithBackpressure(VertxTestContext testContext) {
    caller.assertReadStreamOnCallerContext(ctx -> client.getObjectStream(objectBucket, "stream-object"))
      .onComplete(testContext.succeeding(chunks -> testContext.verify(() -> {
        Buffer all = Buffer.buffer();
        chunks.forEach(all::appendBuffer);
        assertArrayEquals(streamContent, all.getBytes());
        testContext.completeNow();
      })));
  }

  @Test
  void rangedObjectStreamDeliversOnCallerContext(VertxTestContext testContext) {
    caller.assertReadStreamOnCallerContext(ctx -> client.getObjectStream(objectBucket, "stream-object", "bytes=1000-1999"))
      .onComplete(testContext.succeeding(chunks -> testContext.verify(() -> {
        Buffer all = Buffer.buffer();
        chunks.forEach(all::appendBuffer);
        assertArrayEquals(java.util.Arrays.copyOfRange(streamContent, 1000, 2000), all.getBytes());
        testContext.completeNow();
      })));
  }

  @Test
  void emptyObjectStreamEndsOnCallerContext(VertxTestContext testContext) {
    caller.assertReadStreamOnCallerContext(ctx -> client.getObjectStream(objectBucket, "empty-object"))
      .onComplete(testContext.succeeding(chunks -> testContext.verify(() -> {
        assertEquals(0, chunks.stream().mapToInt(Buffer::length).sum());
        testContext.completeNow();
      })));
  }

  @Test
  void getObjectStreamFailureCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.getObjectStream(objectBucket, "no-such-key"))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchKey", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void cancelCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.getObjectStream(objectBucket, "stream-object"))
      .compose(stream -> caller.assertCompletesOnCallerContext(ctx -> stream.cancel()))
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void invalidKeyFailsOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.getObjectStream(objectBucket, ""))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertEquals("The object key must not be empty", e.getMessage());
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
