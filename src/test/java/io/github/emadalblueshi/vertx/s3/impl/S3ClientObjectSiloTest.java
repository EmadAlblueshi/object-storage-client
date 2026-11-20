package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Object operations end to end against Silo (shared backends), over HTTP (signed payloads,
 * inline and via executeBlocking) and HTTPS (UNSIGNED-PAYLOAD). Silo verifies every signature
 * and payload hash server-side. Test data is random and generated at runtime.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ClientObjectSiloTest {

  private Vertx vertx;
  private S3Client http;
  private S3Client https;
  private final String bucket = SignedRequests.bucketName("objects");

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    http = S3Client.create(vertx, S3ClientSiloTest.options(S3TestBackend.sharedSilo()));
    https = S3Client.create(vertx, S3ClientSiloTest.options(S3TestBackend.sharedSiloTls()));
    // Both backends are the same kind of server but separate instances: one bucket each
    http.createBucket(bucket).compose(v -> https.createBucket(bucket)).onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    Future.all(http.close(), https.close()).onComplete(testContext.succeedingThenComplete());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 1000, PayloadMode.MAX_INLINE_HASH_SIZE, PayloadMode.MAX_INLINE_HASH_SIZE + 1, 3 * 1024 * 1024})
  void roundTripOverHttp(int size, VertxTestContext testContext) {
    roundTrip(http, size, testContext);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3 * 1024 * 1024})
  void roundTripOverHttps(int size, VertxTestContext testContext) {
    roundTrip(https, size, testContext);
  }

  /** put, then getObject, headObject and getObjectStream all agree with the data. */
  private void roundTrip(S3Client client, int size, VertxTestContext testContext) {
    byte[] data = random(size);
    String key = "round-trip/" + size + ".bin";
    client.putObject(bucket, key, Buffer.buffer(data))
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
      .onComplete(testContext.succeeding(chunks -> testContext.verify(() -> {
        assertArrayEquals(data, concat(chunks).getBytes());
        testContext.completeNow();
      })));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dir/sub/file.txt", "a b+c=d&e.txt", "ünïcødé/文件.bin", "percent%20literal",
    "question?mark#hash", "tilde~star*(paren)!", "semi;colon,comma@at$dollar", "quote'single\"double",
    "brackets[]{}^|`<>"})
  void specialCharacterKeys(String key, VertxTestContext testContext) {
    Buffer content = Buffer.buffer("content of " + key);
    http.putObject(bucket, key, content)
      .compose(etag -> http.getObject(bucket, key))
      .compose(body -> {
        testContext.verify(() -> assertEquals(content, body));
        return http.deleteObject(bucket, key);
      })
      .compose(v -> http.headObject(bucket, key))
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals(404, assertInstanceOf(S3ServiceException.class, e).getStatusCode());
        testContext.completeNow();
      })));
  }

  @Test
  void contentTypeAndMetadataRoundTrip(VertxTestContext testContext) {
    String key = "with-metadata.json";
    S3PutObjectOptions options = new S3PutObjectOptions().setContentType("application/json")
      .setMetadata(Map.of("Owner-Id", "42", "note", "hello world"));
    https.putObject(bucket, key, Buffer.buffer("{}"), options)
      .compose(etag -> https.headObject(bucket, key))
      .compose(head -> {
        testContext.verify(() -> {
          assertEquals("application/json", head.getContentType());
          assertEquals(Map.of("owner-id", "42", "note", "hello world"), head.getMetadata());
        });
        return https.getObjectStream(bucket, key);
      })
      .compose(stream -> {
        testContext.verify(() -> {
          assertEquals("application/json", stream.metadata().getContentType());
          assertEquals(Map.of("owner-id", "42", "note", "hello world"), stream.metadata().getMetadata());
        });
        return stream.cancel();
      })
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void getObjectOverTheLimitPointsToGetObjectStream(VertxTestContext testContext) {
    S3Client small = S3Client.create(vertx, S3ClientSiloTest.options(S3TestBackend.sharedSilo()).setMaxBufferedObjectSize(1024));
    small.putObject(bucket, "over-limit.bin", Buffer.buffer(random(2048)))
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
    byte[] data = random(24 * 1024 * 1024);
    byte[] expected = MessageDigest.getInstance("SHA-256").digest(data);
    String key = "large.bin";
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    AtomicLong received = new AtomicLong();
    List<Integer> chunkSizes = new ArrayList<>();
    http.putObject(bucket, key, Buffer.buffer(data))
      .compose(etag -> http.getObjectStream(bucket, key))
      .compose(stream -> {
        Promise<Void> done = Promise.promise();
        stream.exceptionHandler(done::tryFail).endHandler(v -> done.tryComplete());
        stream.handler(chunk -> {
          digest.update(chunk.getBytes());
          received.addAndGet(chunk.length());
          chunkSizes.add(chunk.length());
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
        assertTrue(chunkSizes.size() > 100, "streamed in many chunks, not buffered: " + chunkSizes.size());
        testContext.completeNow();
      })));
  }

  @Test
  void ranges(VertxTestContext testContext) {
    String key = "digits.txt";
    http.putObject(bucket, key, Buffer.buffer("0123456789"))
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
    http.putObject(bucket, key, Buffer.buffer("short"))
      .compose(etag -> http.getObjectStream(bucket, key, "bytes=100-"))
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
    http.headObject(bucket, missing).transform(head -> {
      testContext.verify(() -> {
        S3ServiceException e = assertInstanceOf(S3ServiceException.class, head.cause());
        assertEquals(404, e.getStatusCode());
        assertNull(e.getErrorCode(), "HEAD responses have no error document");
      });
      return http.getObject(bucket, missing);
    }).transform(get -> {
      testContext.verify(() -> assertEquals("NoSuchKey", ((S3ServiceException) get.cause()).getErrorCode()));
      return http.getObjectStream(bucket, missing);
    }).transform(stream -> {
      testContext.verify(() -> assertEquals("NoSuchKey", ((S3ServiceException) stream.cause()).getErrorCode()));
      return http.deleteObject(bucket, missing); // deleting a missing key succeeds
    }).onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void cancelledStreamReleasesItsConnection(VertxTestContext testContext) {
    // One pooled connection: the next request can only run if cancel released it
    io.github.emadalblueshi.vertx.s3.S3ClientOptions options = S3ClientSiloTest.options(S3TestBackend.sharedSilo());
    options.getPoolOptions().setHttp1MaxSize(1);
    S3Client single = S3Client.create(vertx, options);
    String key = "cancel.bin";
    single.putObject(bucket, key, Buffer.buffer(random(8 * 1024 * 1024)))
      .compose(etag -> single.getObjectStream(bucket, key))
      .compose(S3ObjectStream::cancel)
      .compose(v -> single.headObject(bucket, key))
      .eventually(single::close)
      .onComplete(testContext.succeeding(head -> testContext.verify(() -> {
        assertEquals(8 * 1024 * 1024, head.getContentLength());
        testContext.completeNow();
      })));
  }

  private Future<String> readRange(String key, String range) {
    return http.getObjectStream(bucket, key, range).compose(stream -> {
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

  private static byte[] random(int size) {
    byte[] data = new byte[size];
    new Random(size).nextBytes(data);
    return data;
  }
}
