package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsResult;
import io.github.emadalblueshi.vertx.s3.S3ListedObject;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Object operations end to end, against one {@link S3TestBackend} per subclass (Silo over HTTP
 * with signed payloads, Silo over HTTPS and the opt-in external backend with UNSIGNED-PAYLOAD).
 * The servers verify every signature and payload hash. Test data is random, generated at runtime.
 * <p>
 * Safe on a real account: all objects live in one uniquely named bucket, created once for the
 * suite; every key is registered before it is written, and cleanup deletes the objects and the
 * bucket even when tests fail.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientObjectTestBase {

  protected Vertx vertx;
  protected S3TestBackend backend;
  protected S3Client client;
  protected final TestResources resources = new TestResources();
  protected final String bucket = resources.bucket(TestResources.uniqueBucketName());

  /** The backend for this suite; may abort the class (skipped) if it isn't available. */
  protected abstract S3TestBackend backend();

  /**
   * Whether the backend rejects keys with empty, {@code .} or {@code ..} segments (Silo, like
   * MinIO, does; on AWS such keys are valid and opaque).
   */
  protected abstract boolean rejectsDotSegmentKeys();

  /**
   * Whether the backend drops a leading {@code /} from keys, so {@code /x} and {@code x} name the
   * same object (Silo does; on AWS they are distinct keys).
   */
  protected abstract boolean stripsLeadingSlash();

  /** Size of the object streamed with a slow consumer. */
  protected int largeObjectSize() {
    return 24 * 1024 * 1024;
  }

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = backend();
    this.client = S3Client.create(vertx, TestResources.clientOptions(backend));
    client.createBucket(bucket).onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    if (client == null) {
      testContext.completeNow();
      return;
    }
    resources.cleanUp(client).eventually(client::close).onComplete(testContext.succeedingThenComplete());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 1000, PayloadMode.MAX_INLINE_HASH_SIZE, PayloadMode.MAX_INLINE_HASH_SIZE + 1, 3 * 1024 * 1024})
  void roundTrip(int size, VertxTestContext testContext) {
    byte[] data = random(size);
    String key = "round-trip/" + size + ".bin";
    put(client, key, Buffer.buffer(data), null)
      .compose(etag -> client.getObject(bucket, key).compose(body -> client.headObject(bucket, key)
        .compose(head -> client.getObjectStream(bucket, key).compose(stream -> {
          testContext.verify(() -> {
            assertNotNull(etag);
            assertArrayEquals(data, body.getBytes());
            assertEquals(etag, head.getEtag());
            assertEquals(size, head.getContentLength());
            assertNotNull(head.getLastModified());
            assertEquals(size, stream.metadata().getContentLength());
            assertEquals(etag, stream.metadata().getEtag());
          });
          return stream.collect(Collectors.toList());
        }))))
      .compose(chunks -> {
        testContext.verify(() -> assertArrayEquals(data, concat(chunks).getBytes()));
        return client.deleteObject(bucket, key);
      })
      .compose(v -> client.headObject(bucket, key))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals(404, assertInstanceOf(S3ServiceException.class, e).getStatusCode());
        testContext.completeNow();
      })));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dir/sub/file.txt", "a b+c=d&e.txt", "ünïcødé/文件.bin", "مستند/ملف.txt", "percent%20literal",
    "question?mark#hash", "tilde~star*(paren)!", "semi;colon,comma@at$dollar", "quote'single\"double",
    "brackets[]{}^|`<>", "..dots..", "trailing-dot."})
  void specialCharacterKeys(String key, VertxTestContext testContext) {
    Buffer content = Buffer.buffer("content of " + key);
    put(client, key, content, null)
      .compose(etag -> client.getObject(bucket, key))
      .compose(body -> {
        testContext.verify(() -> assertEquals(content, body));
        return client.deleteObject(bucket, key);
      })
      .compose(v -> client.headObject(bucket, key))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals(404, assertInstanceOf(S3ServiceException.class, e).getStatusCode());
        testContext.completeNow();
      })));
  }

  /**
   * Keys with empty, {@code .} or {@code ..} segments. The client never normalises them (the
   * signer signs the path as sent); whether the server stores them is backend behaviour.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a//b", "a/./b", "a/../b", "./a", "../a", "a/.", "a/.."})
  void dotSegmentKeys(String key, VertxTestContext testContext) {
    Buffer content = Buffer.buffer("content of " + key);
    Future<Buffer> roundTrip = put(client, key, content, null).compose(etag -> client.getObject(bucket, key));
    if (rejectsDotSegmentKeys()) {
      roundTrip.onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(400, service.getStatusCode(), service.getMessage());
        assertTrue(service.getErrorCode().startsWith("XMinioInvalid"), service.getErrorCode());
        testContext.completeNow();
      })));
    } else {
      roundTrip.compose(body -> {
        testContext.verify(() -> assertEquals(content, body));
        return client.deleteObject(bucket, key);
      }).onComplete(testContext.succeedingThenComplete());
    }
  }

  @Test
  void leadingSlashKey(VertxTestContext testContext) {
    String key = "/leading-slash";
    put(client, key, Buffer.buffer("slash"), null)
      .compose(etag -> client.getObject(bucket, key))
      .compose(body -> {
        testContext.verify(() -> assertEquals("slash", body.toString()));
        resources.key(bucket, "leading-slash");
        return client.headObject(bucket, "leading-slash").transform(ar -> Future.succeededFuture(ar.succeeded()));
      })
      .onComplete(testContext.succeeding(withoutSlashExists -> testContext.verify(() -> {
        assertEquals(stripsLeadingSlash(), withoutSlashExists, "is /leading-slash the same object as leading-slash?");
        testContext.completeNow();
      })));
  }

  @Test
  void contentTypeAndMetadataRoundTrip(VertxTestContext testContext) {
    String key = "with-metadata.json";
    S3PutObjectOptions options = new S3PutObjectOptions().setContentType("application/json")
      .setUserMetadata(Map.of("Owner-Id", "42", "note", "hello world"));
    put(client, key, Buffer.buffer("{}"), options)
      .compose(etag -> client.headObject(bucket, key))
      .compose(head -> {
        testContext.verify(() -> {
          assertEquals("application/json", head.getContentType());
          assertEquals(Map.of("owner-id", "42", "note", "hello world"), head.getUserMetadata());
        });
        return client.getObjectStream(bucket, key);
      })
      .compose(stream -> {
        testContext.verify(() -> {
          assertEquals("application/json", stream.metadata().getContentType());
          assertEquals(Map.of("owner-id", "42", "note", "hello world"), stream.metadata().getUserMetadata());
        });
        return stream.cancel();
      })
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void getObjectOverTheLimitPointsToGetObjectStream(VertxTestContext testContext) {
    S3Client small = S3Client.create(vertx, TestResources.clientOptions(backend).setMaxBufferedObjectSize(1024));
    put(small, "over-limit.bin", Buffer.buffer(random(2048)), null)
      .compose(etag -> small.getObject(bucket, "over-limit.bin"))
      .eventually(small::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertTrue(e.getMessage().contains("(2048 bytes): use getObjectStream"), e.getMessage());
        testContext.completeNow();
      })));
  }

  @Test
  void largeObjectIsStreamedInChunksWithBackpressure(VertxTestContext testContext) throws Exception {
    byte[] data = random(largeObjectSize());
    byte[] expected = MessageDigest.getInstance("SHA-256").digest(data);
    String key = "large.bin";
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    AtomicLong received = new AtomicLong();
    AtomicInteger chunks = new AtomicInteger();
    put(client, key, Buffer.buffer(data), null)
      .compose(etag -> client.getObjectStream(bucket, key))
      .compose(stream -> {
        Promise<Void> done = Promise.promise();
        stream.exceptionHandler(done::tryFail).endHandler(v -> done.tryComplete());
        stream.handler(chunk -> {
          digest.update(chunk.getBytes());
          received.addAndGet(chunk.length());
          chunks.incrementAndGet();
          // A slow consumer: pause after each chunk, resume a little later
          stream.pause();
          vertx.setTimer(1, id -> stream.resume());
        });
        stream.resume();
        return done.future();
      })
      .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
        assertEquals(data.length, received.get());
        assertArrayEquals(expected, digest.digest());
        assertTrue(chunks.get() > 10, "streamed in many chunks, not buffered: " + chunks.get());
        testContext.completeNow();
      })));
  }

  @Test
  void ranges(VertxTestContext testContext) {
    String key = "digits.txt";
    put(client, key, Buffer.buffer("0123456789"), null)
      .compose(etag -> readRange(key, "bytes=0-3"))
      .compose(first -> readRange(key, "bytes=7-").map(rest -> first + "|" + rest))
      .compose(two -> readRange(key, "bytes=-2").map(last -> two + "|" + last))
      .onComplete(testContext.succeeding(all -> testContext.verify(() -> {
        assertEquals("0123|789|89", all);
        testContext.completeNow();
      })));
  }

  @Test
  void rangeOutsideTheObjectIsInvalidRange(VertxTestContext testContext) {
    String key = "short.txt";
    put(client, key, Buffer.buffer("short"), null)
      .compose(etag -> client.getObjectStream(bucket, key, "bytes=100-"))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(416, service.getStatusCode());
        assertEquals("InvalidRange", service.getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void missingObjects(VertxTestContext testContext) {
    String missing = "missing/" + System.nanoTime();
    client.headObject(bucket, missing).transform(head -> {
      testContext.verify(() -> {
        S3ServiceException e = assertInstanceOf(S3ServiceException.class, head.cause());
        assertEquals(404, e.getStatusCode());
        assertNull(e.getErrorCode(), "HEAD responses have no error document");
      });
      return client.getObject(bucket, missing);
    }).transform(get -> {
      testContext.verify(() -> assertEquals("NoSuchKey", assertInstanceOf(S3ServiceException.class, get.cause()).getErrorCode()));
      return client.getObjectStream(bucket, missing);
    }).transform(stream -> {
      testContext.verify(() -> assertEquals("NoSuchKey", assertInstanceOf(S3ServiceException.class, stream.cause()).getErrorCode()));
      return client.deleteObject(bucket, missing); // deleting a missing key succeeds
    }).onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void objectInMissingBucketGivesNoSuchBucket(VertxTestContext testContext) {
    client.getObject(TestResources.uniqueBucketName(), "key").onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(404, service.getStatusCode());
      assertEquals("NoSuchBucket", service.getErrorCode());
      testContext.completeNow();
    })));
  }

  @Test
  void deleteNonEmptyBucketGivesBucketNotEmpty(VertxTestContext testContext) {
    put(client, "keeps-the-bucket-busy.txt", Buffer.buffer("x"), null)
      .compose(etag -> client.deleteBucket(bucket))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(409, service.getStatusCode());
        assertEquals("BucketNotEmpty", service.getErrorCode());
        testContext.completeNow();
      })));
  }

  @Test
  void cancelledStreamReleasesItsConnection(VertxTestContext testContext) {
    // One pooled connection: the next request can only run if cancel released it
    S3ClientOptions options = TestResources.clientOptions(backend);
    options.getPoolOptions().setHttp1MaxSize(1);
    S3Client single = S3Client.create(vertx, options);
    String key = "cancel.bin";
    put(single, key, Buffer.buffer(random(8 * 1024 * 1024)), null)
      .compose(etag -> single.getObjectStream(bucket, key))
      .compose(S3ObjectStream::cancel)
      .compose(v -> single.headObject(bucket, key))
      .eventually(single::close)
      .onComplete(testContext.succeeding(head -> testContext.verify(() -> {
        assertEquals(8 * 1024 * 1024, head.getContentLength());
        testContext.completeNow();
      })));
  }

  // --- ListObjectsV2 (each test lists its own prefix: other tests' objects share the bucket) ---

  @Test
  void listObjectsPagesThroughSeveralPages(VertxTestContext testContext) {
    List<String> keys = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      keys.add("paging/k" + i);
    }
    putAll(keys)
      .compose(v -> listAllPages(new S3ListObjectsOptions().setPrefix("paging/").setMaxKeys(3), new java.util.ArrayList<>()))
      .onComplete(testContext.succeeding(pages -> testContext.verify(() -> {
        assertEquals(List.of(3, 3, 1), pages.stream().map(p -> p.getObjects().size()).collect(Collectors.toList()));
        assertEquals(List.of(true, true, false), pages.stream().map(S3ListObjectsResult::isTruncated).collect(Collectors.toList()));
        assertNotNull(pages.get(0).getNextContinuationToken());
        assertNull(pages.get(2).getNextContinuationToken());
        assertEquals(keys, pages.stream().flatMap(p -> p.getObjects().stream()).map(S3ListedObject::getKey)
          .collect(Collectors.toList()));
        testContext.completeNow();
      })));
  }

  @Test
  void listObjectsStartAfter(VertxTestContext testContext) {
    putAll(List.of("start/a", "start/b", "start/c", "start/d"))
      .compose(v -> client.listObjects(bucket, new S3ListObjectsOptions().setPrefix("start/").setStartAfter("start/b")))
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        assertEquals(List.of("start/c", "start/d"), keysOf(result));
        testContext.completeNow();
      })));
  }

  @Test
  void listObjectsGroupsFoldersWithPrefixAndDelimiter(VertxTestContext testContext) {
    putAll(List.of("folders/readme.txt", "folders/2025/a.txt", "folders/2026/b.txt", "folders/2026/deep/c.txt"))
      .compose(v -> client.listObjects(bucket, new S3ListObjectsOptions().setPrefix("folders/").setDelimiter("/")))
      .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
        assertEquals(List.of("folders/readme.txt"), keysOf(result));
        assertEquals(List.of("folders/2025/", "folders/2026/"), result.getCommonPrefixes());
        testContext.completeNow();
      })));
  }

  @Test
  void listObjectsDecodesSpecialCharacterKeysAndPrefixes(VertxTestContext testContext) {
    String folder = "list-special/مجلد +%&?#/";
    List<String> keys = List.of("list-special/a b+c=d&e.txt", "list-special/percent%20literal",
      "list-special/question?mark#hash", "list-special/ünïcødé 文件", "list-special/مستند.txt",
      "list-special/quote'single\"double", "list-special/tilde~star*(paren)!", folder + "inner.txt");
    putAll(keys)
      .compose(v -> client.listObjects(bucket, new S3ListObjectsOptions().setPrefix("list-special/").setDelimiter("/")))
      .compose(top -> {
        testContext.verify(() -> {
          assertEquals(keys.subList(0, keys.size() - 1).stream().sorted().collect(Collectors.toList()),
            keysOf(top).stream().sorted().collect(Collectors.toList()));
          assertEquals(List.of(folder), top.getCommonPrefixes());
        });
        return client.listObjects(bucket, new S3ListObjectsOptions().setPrefix(folder));
      })
      .onComplete(testContext.succeeding(inner -> testContext.verify(() -> {
        assertEquals(List.of(folder + "inner.txt"), keysOf(inner));
        testContext.completeNow();
      })));
  }

  @Test
  void listedObjectAgreesWithHeadObject(VertxTestContext testContext) {
    String key = "listed/one.bin";
    put(client, key, Buffer.buffer(random(1234)), null)
      .compose(etag -> client.headObject(bucket, key).compose(head ->
        client.listObjects(bucket, new S3ListObjectsOptions().setPrefix("listed/")).map(list -> {
          testContext.verify(() -> {
            S3ListedObject listed = list.getObjects().get(0);
            assertEquals(key, listed.getKey());
            assertEquals(1234, listed.getSize());
            assertEquals(etag, listed.getEtag());
            assertEquals(head.getLastModified().getEpochSecond(), listed.getLastModified().getEpochSecond());
          });
          return list;
        })))
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void listObjectsInMissingBucketGivesNoSuchBucket(VertxTestContext testContext) {
    client.listObjects(TestResources.uniqueBucketName(), null).onComplete(testContext.failing(e -> testContext.verify(() -> {
      S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
      assertEquals(404, service.getStatusCode());
      assertEquals("NoSuchBucket", service.getErrorCode());
      testContext.completeNow();
    })));
  }

  private Future<List<S3ListObjectsResult>> listAllPages(S3ListObjectsOptions options, List<S3ListObjectsResult> pages) {
    return client.listObjects(bucket, options).compose(page -> {
      pages.add(page);
      return page.isTruncated()
        ? listAllPages(new S3ListObjectsOptions(options).setContinuationToken(page.getNextContinuationToken()), pages)
        : Future.succeededFuture(pages);
    });
  }

  private Future<Void> putAll(List<String> keys) {
    Future<Void> chain = Future.succeededFuture();
    for (String key : keys) {
      chain = chain.compose(v -> put(client, key, Buffer.buffer(key), null).mapEmpty());
    }
    return chain;
  }

  private static List<String> keysOf(S3ListObjectsResult result) {
    return result.getObjects().stream().map(S3ListedObject::getKey).collect(Collectors.toList());
  }

  /** Puts an object in the suite's bucket, registering the key for cleanup first. */
  protected Future<String> put(S3Client with, String key, Buffer body, S3PutObjectOptions options) {
    resources.key(bucket, key);
    return with.putObject(bucket, key, body, options);
  }

  private Future<String> readRange(String key, String range) {
    return client.getObjectStream(bucket, key, range).compose(stream -> {
      long length = stream.metadata().getContentLength();
      return stream.collect(Collectors.toList()).map(chunks -> {
        String text = concat(chunks).toString();
        assertEquals(text.length(), length, "content length is the range length");
        return text;
      });
    });
  }

  private static Buffer concat(List<Buffer> chunks) {
    Buffer all = Buffer.buffer();
    chunks.forEach(all::appendBuffer);
    return all;
  }

  protected static byte[] random(int size) {
    byte[] data = new byte[size];
    new Random(size).nextBytes(data);
    return data;
  }
}
