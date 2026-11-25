package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.context.CallerContext;
import io.github.emadalblueshi.vertx.s3.context.CallerKind;
import io.github.emadalblueshi.vertx.s3.impl.MultipartTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Context correctness of every public {@link S3Client} method, against Silo: successes and
 * failures must complete on the caller's context, for each {@link CallerKind} (one subclass
 * each; the virtual-thread one is tagged {@code virtual-threads}).
 */
@ExtendWith(NettyLeakCheck.class)
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
  void multipartOperationsCompleteOnCallerContext(VertxTestContext testContext) {
    String key = "multipart-" + kind();
    AtomicReference<String> uploadId = new AtomicReference<>();
    caller.assertCompletesOnCallerContext(ctx -> client.createMultipartUpload(objectBucket, key,
        new S3PutObjectOptions().setContentType("video/mp4")))
      .compose(id -> {
        uploadId.set(id);
        // A fixture, outside the caller: the client has no public uploadPart
        return MultipartTestSupport.uploadPart(vertx, backend, objectBucket, key, id, 1, Buffer.buffer("one part"));
      })
      .compose(etag -> caller.assertCompletesOnCallerContext(ctx -> client.listParts(objectBucket, key, uploadId.get())))
      .compose(parts -> {
        testContext.verify(() -> assertEquals(1, parts.size()));
        return caller.assertCompletesOnCallerContext(ctx -> client.listMultipartUploads(objectBucket, null))
          .map(uploads -> {
            testContext.verify(() -> assertTrue(uploads.stream().anyMatch(u -> u.getUploadId().equals(uploadId.get()))));
            return parts;
          });
      })
      .compose(parts -> caller.assertCompletesOnCallerContext(ctx -> client.completeMultipartUpload(objectBucket, key,
        uploadId.get(), List.of(new S3CompletedPart(1, parts.get(0).getEtag())))))
      .compose(etag -> client.headObject(objectBucket, key))
      .onComplete(testContext.succeeding(head -> testContext.verify(() -> {
        assertEquals("video/mp4", head.getContentType());
        assertEquals(8, head.getContentLength());
        testContext.completeNow();
      })));
  }

  @Test
  void abortCompletesOnCallerContextAndLaterCallsFailThere(VertxTestContext testContext) {
    String key = "aborted-" + kind();
    AtomicReference<String> uploadId = new AtomicReference<>();
    client.createMultipartUpload(objectBucket, key, null)
      .compose(id -> {
        uploadId.set(id);
        return caller.assertCompletesOnCallerContext(ctx -> client.abortMultipartUpload(objectBucket, key, id));
      })
      .compose(v -> caller.assertCompletesOnCallerContext(ctx -> client.listParts(objectBucket, key, uploadId.get())))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchUpload", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void completeServerFailureCompletesOnCallerContext(VertxTestContext testContext) {
    String key = "wrong-etag-" + kind();
    AtomicReference<String> uploadId = new AtomicReference<>();
    client.createMultipartUpload(objectBucket, key, null)
      .compose(id -> {
        uploadId.set(id);
        return caller.assertCompletesOnCallerContext(ctx -> client.completeMultipartUpload(objectBucket, key, id,
          List.of(new S3CompletedPart(1, "\"not-an-etag\""))));
      })
      .transform(ar -> client.abortMultipartUpload(objectBucket, key, uploadId.get()).transform(cleanup -> ar.failed()
        ? Future.<String>failedFuture(ar.cause())
        : Future.<String>succeededFuture(ar.result())))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("InvalidPart", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void invalidMultipartArgumentsFailOnCallerContext(VertxTestContext testContext) {
    // Fail before any I/O: the immediate-failure path must still complete on the caller's context
    caller.assertCompletesOnCallerContext(ctx -> client.completeMultipartUpload(objectBucket, "k", "up-1", List.of()))
      .transform(empty -> {
        testContext.verify(() -> assertInstanceOf(S3ClientException.class, empty.cause()));
        return caller.assertCompletesOnCallerContext(ctx -> client.listMultipartUploads(objectBucket, "bad\uD800"));
      })
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        testContext.completeNow();
      })));
  }

  /**
   * Streamed uploads from an {@code AsyncFile} opened outside the caller, so its events arrive on
   * another context: a small one (a single PutObject) and one just over a part (multipart; over
   * Silo's plain HTTP each part's SHA-256 is computed as it streams in and verified by Silo).
   */
  @Test
  void streamedUploadsCompleteOnCallerContext(VertxTestContext testContext) {
    byte[] small = new byte[1024];
    byte[] large = new byte[8 * 1024 * 1024 + 1];
    new java.util.Random(7).nextBytes(small);
    new java.util.Random(8).nextBytes(large);
    String smallKey = "streamed-small-" + kind();
    String largeKey = "streamed-large-" + kind();
    openTemp(small)
      .compose(file -> caller.assertCompletesOnCallerContext(ctx -> client.putObject(objectBucket, smallKey, file)))
      .compose(etag -> openTemp(large))
      .compose(file -> caller.assertCompletesOnCallerContext(ctx -> client.putObject(objectBucket, largeKey, file,
        new S3PutObjectOptions().setContentType("video/mp4"))))
      .compose(etag -> {
        testContext.verify(() -> assertTrue(etag.endsWith("-2\""), etag));
        return client.getObject(objectBucket, smallKey);
      })
      .compose(body -> {
        testContext.verify(() -> assertArrayEquals(small, body.getBytes()));
        return client.headObject(objectBucket, largeKey);
      })
      .onComplete(testContext.succeeding(head -> testContext.verify(() -> {
        assertEquals(large.length, head.getContentLength());
        assertEquals("video/mp4", head.getContentType());
        testContext.completeNow();
      })));
  }

  /**
   * File uploads: a small one (one PutObject) and one just over a part (a multipart upload of two
   * ranges streamed from the file; over Silo's plain HTTP each range is hashed first and the
   * signature verified by Silo).
   */
  @Test
  void fileUploadsCompleteOnCallerContext(VertxTestContext testContext) {
    byte[] small = new byte[2048];
    byte[] large = new byte[8 * 1024 * 1024 + 1];
    new java.util.Random(9).nextBytes(small);
    new java.util.Random(10).nextBytes(large);
    String smallKey = "file-small-" + kind();
    String largeKey = "file-large-" + kind();
    uploadTemp(small, smallKey)
      .compose(etag -> uploadTemp(large, largeKey))
      .compose(etag -> {
        testContext.verify(() -> assertTrue(etag.endsWith("-2\""), etag));
        return client.getObject(objectBucket, smallKey);
      })
      .compose(body -> {
        testContext.verify(() -> assertArrayEquals(small, body.getBytes()));
        return client.getObjectStream(objectBucket, largeKey).compose(stream -> stream.collect(java.util.stream.Collectors.toList()));
      })
      .onComplete(testContext.succeeding(chunks -> testContext.verify(() -> {
        Buffer all = Buffer.buffer();
        chunks.forEach(all::appendBuffer);
        assertArrayEquals(large, all.getBytes());
        testContext.completeNow();
      })));
  }

  /** Writes {@code content} to a temporary file and uploads it with putFile as the caller. */
  private Future<String> uploadTemp(byte[] content, String key) {
    return vertx.fileSystem().createTempFile("vertx-s3-ctx-", ".bin")
      .compose(path -> vertx.fileSystem().writeFile(path, Buffer.buffer(content))
        .compose(v -> caller.assertCompletesOnCallerContext(ctx -> client.putFile(objectBucket, key, path,
          new S3PutObjectOptions().setContentType("application/octet-stream"))))
        .compose(etag -> vertx.fileSystem().delete(path).map(etag)));
  }

  @Test
  void fileUploadFailureCompletesOnCallerContext(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> client.putFile(objectBucket, "k", "/no/such/file-" + kind()))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        testContext.completeNow();
      })));
  }

  @Test
  void streamedUploadFailureCompletesOnCallerContext(VertxTestContext testContext) {
    String missing = "ctx-missing-" + UUID.randomUUID().toString().substring(0, 8);
    openTemp(new byte[10])
      .compose(file -> caller.assertCompletesOnCallerContext(ctx -> client.putObject(missing, "k", file)))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("NoSuchBucket", assertInstanceOf(S3ServiceException.class, e).getErrorCode());
        testContext.completeNow();
      })));
  }

  /** A temporary file with {@code content}, opened for reading: a real stream owned by another context. */
  private Future<io.vertx.core.file.AsyncFile> openTemp(byte[] content) {
    return vertx.fileSystem().createTempFile("vertx-s3-ctx-", ".bin")
      .compose(path -> vertx.fileSystem().writeFile(path, Buffer.buffer(content))
        .compose(v -> vertx.fileSystem().open(path, new io.vertx.core.file.OpenOptions().setRead(true)
          .setDeleteOnClose(true))));
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
