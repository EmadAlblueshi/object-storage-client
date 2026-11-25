package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3MultipartUpload;
import io.github.emadalblueshi.vertx.s3.S3PresignUploadPartOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.S3UploadedPart;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Multipart uploads end to end, the way browser and mobile clients use them: the client library
 * creates, lists, completes and aborts uploads, and the parts go through presigned part URLs with a
 * plain Vert.x {@code HttpClient}, without credentials. One {@link S3TestBackend} per subclass (Silo
 * over HTTP and HTTPS, and the opt-in external backend); server differences are explicit
 * expectations in the subclasses. Part data is random, generated at runtime; parts other than the
 * last are the 5 MiB minimum.
 * <p>
 * Safe on a real account: one uniquely named bucket for the suite, every key registered before it
 * is written, and at the end every upload still open in the bucket is aborted (with
 * {@link S3Client#listMultipartUploads}) before the objects and the bucket are deleted.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class S3ClientMultipartTestBase {

  private static final int MIN_PART = 5 * 1024 * 1024;
  private static final Pattern ERROR_CODE = Pattern.compile("<Code>([^<]*)</Code>");

  protected Vertx vertx;
  protected S3TestBackend backend;
  protected S3Client client;
  private S3Presigner presigner;
  private HttpClientAgent http;
  private Context context;
  protected final TestResources resources = new TestResources();
  protected final String bucket = resources.bucket(TestResources.uniqueBucketName());

  /** The backend for this suite; may abort the class (skipped) if it isn't available. */
  protected abstract S3TestBackend backend();

  /** Whether a ListMultipartUploads prefix matches the start of keys (AWS), not only whole keys. */
  protected boolean prefixMatchesKeyStarts() {
    return true;
  }

  /** Whether aborting an upload that no longer exists fails with 404 NoSuchUpload (AWS), rather than succeeding. */
  protected boolean abortOfFinishedUploadFails() {
    return true;
  }

  /** The 404 error code of ListParts for an aborted upload: NoSuchUpload on AWS. */
  protected String listPartsAfterAbortCode() {
    return "NoSuchUpload";
  }

  /** Whether the server pages ListMultipartUploads (honours max-uploads and the markers), as AWS does. */
  protected boolean pagesUploadListings() {
    return true;
  }

  @BeforeAll
  void setUp(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    this.backend = backend();
    this.client = S3Client.create(vertx, TestResources.clientOptions(backend));
    this.presigner = client.presigner();
    this.http = vertx.createHttpClient(backend.httpClientOptions());
    this.context = vertx.getOrCreateContext();
    client.createBucket(bucket).onComplete(testContext.succeedingThenComplete());
  }

  @AfterAll
  void tearDown(VertxTestContext testContext) {
    if (client == null) {
      testContext.completeNow();
      return;
    }
    client.listMultipartUploads(bucket, null)
      .compose(open -> Future.join(open.stream()
        .map(upload -> client.abortMultipartUpload(bucket, upload.getKey(), upload.getUploadId()))
        .collect(Collectors.toList())))
      .otherwiseEmpty()
      .compose(v -> resources.cleanUp(client))
      .eventually(client::close)
      .eventually(http::close)
      .onComplete(testContext.succeedingThenComplete());
  }

  /** A 5 MiB part and a small last part, through presigned URLs; the object gets the create-time content type. */
  @Test
  void presignedPartsRoundTrip(VertxTestContext testContext) {
    String key = resources.key(bucket, "round-trip/video.mp4");
    Buffer part1 = random(MIN_PART, 1);
    Buffer part2 = random(1024, 2);
    client.createMultipartUpload(bucket, key, new S3PutObjectOptions().setContentType("video/mp4"))
      .compose(uploadId -> uploadPart(key, uploadId, 1, part1)
        .compose(etag1 -> uploadPart(key, uploadId, 2, part2)
          .compose(etag2 -> client.completeMultipartUpload(bucket, key, uploadId,
            List.of(new S3CompletedPart(1, etag1), new S3CompletedPart(2, etag2))))))
      .compose(etag -> {
        assertTrue(etag.endsWith("-2\""), etag);
        return client.headObject(bucket, key);
      })
      .compose(head -> {
        assertEquals(MIN_PART + 1024, head.getContentLength());
        assertEquals("video/mp4", head.getContentType());
        return digestOf(key);
      })
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part1, part2), digest);
        testContext.completeNow();
      })));
  }

  /** Parts uploaded at the same time, reported in another order: the client sends them in part order. */
  @Test
  void parallelPartsReportedInAnyOrder(VertxTestContext testContext) {
    String key = resources.key(bucket, "parallel/video.mp4");
    Buffer part1 = random(MIN_PART, 11);
    Buffer part2 = random(MIN_PART, 12);
    Buffer part3 = random(100, 13);
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> {
        Future<String> upload1 = uploadPart(key, uploadId, 1, part1);
        Future<String> upload2 = uploadPart(key, uploadId, 2, part2);
        Future<String> upload3 = uploadPart(key, uploadId, 3, part3);
        return Future.all(upload1, upload2, upload3).compose(all -> client.completeMultipartUpload(bucket, key, uploadId,
          List.of(new S3CompletedPart(3, upload3.result()), new S3CompletedPart(1, upload1.result()),
            new S3CompletedPart(2, upload2.result()))));
      })
      .compose(etag -> digestOf(key))
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part1, part2, part3), digest);
        testContext.completeNow();
      })));
  }

  /** A client that lost its ETags resumes: listParts gives them back, and only the missing part is uploaded. */
  @Test
  void resumeWithListParts(VertxTestContext testContext) {
    String key = resources.key(bucket, "resume/video.mp4");
    Buffer part1 = random(MIN_PART, 21);
    Buffer part2 = random(MIN_PART, 22);
    Buffer part3 = random(10, 23);
    List<String> uploadedEtags = new ArrayList<>();
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> uploadPart(key, uploadId, 1, part1)
        .compose(etag -> {
          uploadedEtags.add(etag);
          return uploadPart(key, uploadId, 2, part2);
        })
        .compose(etag -> {
          uploadedEtags.add(etag);
          // The client is gone; later, with only the stored upload id:
          return client.listParts(bucket, key, uploadId);
        })
        .compose(listed -> {
          assertEquals(List.of(1, 2), listed.stream().map(S3UploadedPart::getPartNumber).collect(Collectors.toList()));
          assertEquals(uploadedEtags, listed.stream().map(S3UploadedPart::getEtag).collect(Collectors.toList()));
          assertEquals(List.of((long) MIN_PART, (long) MIN_PART),
            listed.stream().map(S3UploadedPart::getSize).collect(Collectors.toList()));
          assertNotNull(listed.get(0).getLastModified());
          return uploadPart(key, uploadId, 3, part3).compose(etag3 -> {
            List<S3CompletedPart> parts = listed.stream()
              .map(p -> new S3CompletedPart(p.getPartNumber(), p.getEtag())).collect(Collectors.toList());
            parts.add(new S3CompletedPart(3, etag3));
            return client.completeMultipartUpload(bucket, key, uploadId, parts);
          });
        }))
      .compose(etag -> digestOf(key))
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part1, part2, part3), digest);
        testContext.completeNow();
      })));
  }

  /** listParts follows the part-number marker across pages (pages of 2 here). */
  @Test
  void listPartsFollowsPages(VertxTestContext testContext) {
    String key = resources.key(bucket, "pages/parts.bin");
    S3ClientImpl impl = (S3ClientImpl) client;
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> {
        Future<Void> chain = Future.succeededFuture();
        for (int n = 1; n <= 5; n++) {
          int partNumber = n;
          chain = chain.compose(v -> uploadPart(key, uploadId, partNumber, random(partNumber, partNumber)).mapEmpty());
        }
        return chain.compose(v -> impl.listParts(bucket, key, uploadId, 2))
          .compose(parts -> client.abortMultipartUpload(bucket, key, uploadId).map(parts));
      })
      .onComplete(testContext.succeeding(parts -> testContext.verify(() -> {
        assertEquals(List.of(1, 2, 3, 4, 5), parts.stream().map(S3UploadedPart::getPartNumber).collect(Collectors.toList()));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), parts.stream().map(S3UploadedPart::getSize).collect(Collectors.toList()));
        testContext.completeNow();
      })));
  }

  /**
   * Abort removes the upload: its parts can't be listed, a fresh part URL is refused, and no object
   * exists. A second abort fails with 404 NoSuchUpload on AWS and succeeds on some servers.
   */
  @Test
  void abortRemovesTheUpload(VertxTestContext testContext) {
    String key = resources.key(bucket, "aborted/video.mp4");
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> uploadPart(key, uploadId, 1, random(100, 31))
        .compose(etag -> client.abortMultipartUpload(bucket, key, uploadId))
        .compose(v -> client.listParts(bucket, key, uploadId).transform(ar -> {
          assertGone(ar.cause(), listPartsAfterAbortCode());
          return send(HttpMethod.PUT, presigner.presignUploadPart(bucket, key, uploadId, 2, 10, TimeUnit.MINUTES),
            random(100, 32), Map.of());
        }))
        .compose(response -> {
          expectError(response, 404, "NoSuchUpload");
          return client.headObject(bucket, key).transform(ar -> {
            assertEquals(404, assertInstanceOf(S3ServiceException.class, ar.cause()).getStatusCode());
            return client.abortMultipartUpload(bucket, key, uploadId);
          });
        })
        .transform(secondAbort -> {
          if (abortOfFinishedUploadFails()) {
            assertNoSuchUpload(secondAbort.cause());
          } else {
            assertTrue(secondAbort.succeeded(), String.valueOf(secondAbort.cause()));
          }
          return Future.<Void>succeededFuture();
        }))
      .onComplete(testContext.succeedingThenComplete());
  }

  /** Completing with a wrong ETag fails with InvalidPart and leaves the upload open, so it can be completed correctly. */
  @Test
  void wrongEtagFailsAndLeavesTheUploadOpen(VertxTestContext testContext) {
    String key = resources.key(bucket, "wrong-etag/doc.bin");
    Buffer part = random(1000, 41);
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> uploadPart(key, uploadId, 1, part)
        .compose(etag -> client.completeMultipartUpload(bucket, key, uploadId,
            List.of(new S3CompletedPart(1, "\"00000000000000000000000000000000\"")))
          .transform(ar -> {
            S3ServiceException e = assertInstanceOf(S3ServiceException.class, ar.cause());
            assertEquals(400, e.getStatusCode(), e.getMessage());
            assertEquals("InvalidPart", e.getErrorCode());
            return client.listParts(bucket, key, uploadId);
          })
          .compose(parts -> {
            assertEquals(1, parts.size());
            return client.completeMultipartUpload(bucket, key, uploadId, List.of(new S3CompletedPart(1, etag)));
          })))
      .compose(etag -> client.headObject(bucket, key))
      .onComplete(testContext.succeeding(head -> testContext.verify(() -> {
        assertEquals(1000, head.getContentLength());
        testContext.completeNow();
      })));
  }

  /**
   * S3's part-order rules: part numbers may have gaps, the list may come in any order (the client
   * sorts it), and a repeated part number is rejected before any request.
   */
  @Test
  void partOrderFollowsS3Rules(VertxTestContext testContext) {
    String key = resources.key(bucket, "order/gaps.bin");
    Buffer part1 = random(MIN_PART, 51);
    Buffer part7 = random(10, 57);
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> uploadPart(key, uploadId, 1, part1)
        .compose(etag1 -> uploadPart(key, uploadId, 7, part7)
          .compose(etag7 -> client.completeMultipartUpload(bucket, key, uploadId,
              List.of(new S3CompletedPart(7, etag7), new S3CompletedPart(1, etag1), new S3CompletedPart(7, etag7)))
            .transform(duplicate -> {
              assertInstanceOf(S3ClientException.class, duplicate.cause());
              return client.completeMultipartUpload(bucket, key, uploadId,
                List.of(new S3CompletedPart(7, etag7), new S3CompletedPart(1, etag1)));
            }))))
      .compose(etag -> digestOf(key))
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part1, part7), digest);
        testContext.completeNow();
      })));
  }

  /** A part other than the last that is under 5 MiB makes the completion fail with EntityTooSmall. */
  @Test
  void smallPartOtherThanTheLastIsRejectedOnCompletion(VertxTestContext testContext) {
    String key = resources.key(bucket, "too-small/doc.bin");
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> uploadPart(key, uploadId, 1, random(1, 61))
        .compose(etag1 -> uploadPart(key, uploadId, 2, random(1, 62))
          .compose(etag2 -> client.completeMultipartUpload(bucket, key, uploadId,
            List.of(new S3CompletedPart(1, etag1), new S3CompletedPart(2, etag2)))))
        .transform(ar -> client.abortMultipartUpload(bucket, key, uploadId).transform(v -> Future.succeededFuture(ar))))
      .onComplete(testContext.succeeding(ar -> testContext.verify(() -> {
        S3ServiceException e = assertInstanceOf(S3ServiceException.class, ar.cause());
        assertEquals(400, e.getStatusCode(), e.getMessage());
        assertEquals("EntityTooSmall", e.getErrorCode());
        testContext.completeNow();
      })));
  }

  /** A signed Content-MD5 pins a part's bytes: other bytes are rejected with BadDigest. */
  @Test
  void partContentMd5PinsTheBytes(VertxTestContext testContext) {
    String key = resources.key(bucket, "md5/doc.bin");
    Buffer part = random(2000, 71);
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> {
        S3PresignedRequest request = presigner.presignUploadPart(bucket, key, uploadId, 1, 10, TimeUnit.MINUTES,
          new S3PresignUploadPartOptions().setContentMd5(base64Md5(part)));
        assertEquals(Map.of("Content-MD5", base64Md5(part)), request.getHeaders());
        return send(HttpMethod.PUT, request.getUrl(), random(2000, 72), request.getHeaders())
          .compose(rejected -> {
            expectError(rejected, 400, "BadDigest");
            return send(HttpMethod.PUT, request.getUrl(), part, request.getHeaders());
          })
          .compose(accepted -> {
            assertEquals(200, accepted.status, accepted.body.toString());
            return client.completeMultipartUpload(bucket, key, uploadId,
              List.of(new S3CompletedPart(1, accepted.headers.get("ETag"))));
          });
      })
      .compose(etag -> digestOf(key))
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part), digest);
        testContext.completeNow();
      })));
  }

  /**
   * Each part signs its exact length, the last one the remainder: a part sent with another length
   * (shorter for the first, longer for the last) is rejected by the signature check, the exact
   * parts, sent with the returned headers as is, complete the object.
   */
  @Test
  void partContentLengthPinsEachPartsSize(VertxTestContext testContext) {
    String key = resources.key(bucket, "length/video.mp4");
    Buffer part1 = random(MIN_PART, 81);
    Buffer part2 = random(1024, 82);
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> {
        S3PresignedRequest first = presigner.presignUploadPart(bucket, key, uploadId, 1, 10, TimeUnit.MINUTES,
          new S3PresignUploadPartOptions().setContentLength((long) MIN_PART));
        S3PresignedRequest last = presigner.presignUploadPart(bucket, key, uploadId, 2, 10, TimeUnit.MINUTES,
          new S3PresignUploadPartOptions().setContentLength(1024L));
        assertEquals(Map.of("Content-Length", Integer.toString(MIN_PART)), first.getHeaders());
        assertEquals(Map.of("Content-Length", "1024"), last.getHeaders());
        return send(HttpMethod.PUT, first.getUrl(), part1.slice(0, MIN_PART - 1), Map.of())
          .compose(rejected -> {
            expectError(rejected, 403, "SignatureDoesNotMatch");
            return send(HttpMethod.PUT, last.getUrl(), part2.copy().appendByte((byte) 0), Map.of());
          })
          .compose(rejected -> {
            expectError(rejected, 403, "SignatureDoesNotMatch");
            return send(HttpMethod.PUT, first.getUrl(), part1, first.getHeaders());
          })
          .compose(accepted1 -> {
            assertEquals(200, accepted1.status, accepted1.body.toString());
            return send(HttpMethod.PUT, last.getUrl(), part2, last.getHeaders()).compose(accepted2 -> {
              assertEquals(200, accepted2.status, accepted2.body.toString());
              return client.completeMultipartUpload(bucket, key, uploadId, List.of(
                new S3CompletedPart(1, accepted1.headers.get("ETag")), new S3CompletedPart(2, accepted2.headers.get("ETag"))));
            });
          });
      })
      .compose(etag -> client.headObject(bucket, key))
      .compose(head -> {
        assertEquals(MIN_PART + 1024, head.getContentLength());
        return digestOf(key);
      })
      .onComplete(testContext.succeeding(digest -> testContext.verify(() -> {
        assertArrayEquals(md5(part1, part2), digest);
        testContext.completeNow();
      })));
  }

  /**
   * A cleanup job finds an upload that was created but never recorded, and aborts it. Listing the
   * whole bucket and listing by the full key find it everywhere; a shorter prefix finds it where
   * prefixes match the start of keys.
   */
  @Test
  void orphanedUploadIsFoundAndAborted(VertxTestContext testContext) {
    String key = resources.key(bucket, "orphans/" + UUID.randomUUID() + ".mp4");
    client.createMultipartUpload(bucket, key, null)
      .compose(uploadId -> client.listMultipartUploads(bucket, null)
        .compose(all -> {
          assertTrue(contains(all, key, uploadId), "the whole bucket: " + all);
          assertNotNull(all.stream().filter(u -> u.getUploadId().equals(uploadId)).findFirst().get().getInitiated());
          return client.listMultipartUploads(bucket, key);
        })
        .compose(byKey -> {
          assertEquals(List.of(uploadId), byKey.stream().map(S3MultipartUpload::getUploadId).collect(Collectors.toList()));
          return client.listMultipartUploads(bucket, "orphans/");
        })
        .compose(byPrefix -> {
          assertEquals(prefixMatchesKeyStarts(), contains(byPrefix, key, uploadId), "the orphans/ prefix: " + byPrefix);
          return client.abortMultipartUpload(bucket, key, uploadId);
        })
        .compose(v -> client.listMultipartUploads(bucket, null))
        .map(after -> {
          assertFalse(contains(after, key, uploadId), "aborted: " + after);
          return null;
        }))
      .onComplete(testContext.succeedingThenComplete());
  }

  /**
   * listMultipartUploads follows the key and upload-id markers across pages (pages of 1 here; two
   * uploads share a key, so the upload-id marker matters) and returns every upload in key order.
   * Where the server pages, a limit of 2 pages is reached; a server that returns everything at
   * once never reaches it.
   */
  @Test
  void listMultipartUploadsFollowsPages(VertxTestContext testContext) {
    S3ClientImpl impl = (S3ClientImpl) client;
    String prefix = "paging-" + UUID.randomUUID().toString().substring(0, 8) + "/";
    List<String> keys = List.of(prefix + "a.mp4", prefix + "a.mp4", prefix + "b.mp4");
    keys.forEach(key -> resources.key(bucket, key));
    String listPrefix = prefixMatchesKeyStarts() ? prefix : null;
    List<String> created = new ArrayList<>();
    Future<Void> creates = Future.succeededFuture();
    for (String key : keys) {
      creates = creates.compose(v -> client.createMultipartUpload(bucket, key, null).map(id -> {
        created.add(key + " " + id);
        return null;
      }));
    }
    creates
      .compose(v -> impl.listMultipartUploads(bucket, listPrefix, 1, 100))
      .compose(listed -> {
        List<String> ours = listed.stream().filter(u -> u.getKey().startsWith(prefix))
          .map(u -> u.getKey() + " " + u.getUploadId()).collect(Collectors.toList());
        assertEquals(created.stream().sorted().collect(Collectors.toSet()), Set.copyOf(ours));
        assertEquals(keys, ours.stream().map(s -> s.substring(0, s.indexOf(' '))).collect(Collectors.toList()), "key order");
        return impl.listMultipartUploads(bucket, listPrefix, 1, 2).transform(Future::succeededFuture);
      })
      .compose(limited -> {
        if (pagesUploadListings()) {
          assertInstanceOf(S3ClientException.class, limited.cause());
          assertTrue(limited.cause().getMessage().startsWith("More than 2 multipart uploads are open"), limited.cause().getMessage());
        } else {
          assertTrue(limited.succeeded(), String.valueOf(limited.cause()));
        }
        return Future.join(created.stream().map(s -> client.abortMultipartUpload(bucket, s.substring(0, s.indexOf(' ')),
          s.substring(s.indexOf(' ') + 1))).collect(Collectors.toList()));
      })
      .onComplete(testContext.succeedingThenComplete());
  }

  // --- helpers -------------------------------------------------------------------------------------

  /** Uploads a part through a presigned URL; the future completes with its ETag. */
  private Future<String> uploadPart(String key, String uploadId, int partNumber, Buffer body) {
    String url = presigner.presignUploadPart(bucket, key, uploadId, partNumber, 10, TimeUnit.MINUTES);
    return send(HttpMethod.PUT, url, body, Map.of()).map(response -> {
      assertEquals(200, response.status, response.body.toString());
      String etag = response.headers.get("ETag");
      assertNotNull(etag, "the part's ETag");
      return etag;
    });
  }

  /** The MD5 digest of an object, streamed (parts are larger than getObject's limit). */
  private Future<byte[]> digestOf(String key) {
    return client.getObjectStream(bucket, key).compose(stream -> {
      MessageDigest digest = md5();
      Promise<byte[]> done = Promise.promise();
      stream.handler(chunk -> digest.update(chunk.getBytes()))
        .exceptionHandler(done::tryFail)
        .endHandler(v -> done.tryComplete(digest.digest()))
        .resume();
      return done.future();
    });
  }

  /**
   * Sends {@code url} as is, with exactly {@code headers} and no credentials. The whole exchange is
   * assembled in one task on a Vert.x context, so the response handlers are attached before the
   * response can arrive.
   */
  private Future<Response> send(HttpMethod method, String url, Buffer body, Map<String, String> headers) {
    RequestOptions options = new RequestOptions().setMethod(method).setAbsoluteURI(url);
    headers.forEach(options::putHeader);
    Promise<Response> promise = Promise.promise();
    context.runOnContext(v -> http.request(options)
      .compose(request -> request.send(body))
      .compose(response -> response.body().map(content -> new Response(response.statusCode(), response.headers(), content)))
      .onComplete(promise));
    return promise.future();
  }

  private static boolean contains(List<S3MultipartUpload> uploads, String key, String uploadId) {
    return uploads.stream().anyMatch(u -> u.getKey().equals(key) && u.getUploadId().equals(uploadId));
  }

  private static void assertNoSuchUpload(Throwable failure) {
    assertGone(failure, "NoSuchUpload");
  }

  private static void assertGone(Throwable failure, String code) {
    S3ServiceException e = assertInstanceOf(S3ServiceException.class, failure);
    assertEquals(404, e.getStatusCode(), e.getMessage());
    assertEquals(code, e.getErrorCode());
  }

  private static void expectError(Response response, int status, String code) {
    assertEquals(status, response.status, response.body.toString());
    Matcher matcher = ERROR_CODE.matcher(response.body.toString());
    assertTrue(matcher.find(), response.body.toString());
    assertEquals(code, matcher.group(1), response.body.toString());
  }

  private static Buffer random(int size, long seed) {
    byte[] data = new byte[size];
    new Random(seed).nextBytes(data);
    return Buffer.buffer(data);
  }

  private static byte[] md5(Buffer... parts) {
    MessageDigest digest = md5();
    for (Buffer part : parts) {
      digest.update(part.getBytes());
    }
    return digest.digest();
  }

  private static String base64Md5(Buffer body) {
    return Base64.getEncoder().encodeToString(md5(body));
  }

  private static MessageDigest md5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static final class Response {
    final int status;
    final MultiMap headers;
    final Buffer body;

    Response(int status, MultiMap headers, Buffer body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }
  }
}
