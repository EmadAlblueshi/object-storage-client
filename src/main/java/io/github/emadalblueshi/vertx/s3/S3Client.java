package io.github.emadalblueshi.vertx.s3;

import io.github.emadalblueshi.vertx.s3.impl.S3ClientImpl;
import io.vertx.codegen.annotations.CacheReturn;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

/**
 * Asynchronous client for Amazon S3 and S3-compatible storage.
 * <p>
 * <strong>Context guarantee:</strong> every asynchronous method captures the caller's Vert.x
 * context at call time and completes its {@link Future} on that context, whether the caller is
 * an event-loop, worker or virtual-thread verticle. Called from a non-Vert.x thread, the Future
 * completes on a Vert.x event-loop context.
 * <p>
 * Failures are {@link S3Exception}s: an {@link S3ServiceException} when the server returned an
 * error response, an {@link S3ClientException} for everything else (connection, TLS, invalid
 * response, invalid argument, closed client). Methods never throw; failures are reported through
 * the returned Future.
 * <p>
 * A client is thread-safe and can be shared between verticles. It is bound to the {@code Vertx}
 * it was created with. Created inside a verticle, it is closed automatically when that verticle
 * is undeployed, like a Vert.x {@code HttpClient}. Otherwise it is closed with its {@code Vertx}.
 * Don't use a client after its {@code Vertx} is closed: Vert.x can no longer deliver results.
 */
@VertxGen
public interface S3Client {

  /**
   * Creates a client.
   *
   * @param vertx the Vert.x instance the client is bound to
   * @param options the options (copied; later changes have no effect)
   * @return the client
   * @throws IllegalArgumentException if the options are invalid, e.g. a missing endpoint, region
   *     or credentials
   * @throws NullPointerException if {@code vertx} or {@code options} is {@code null}
   * @throws IllegalStateException if the verticle context creating the client is already closed
   *     (undeployed): nothing could close the client then
   */
  static S3Client create(Vertx vertx, S3ClientOptions options) {
    return new S3ClientImpl(vertx, options);
  }

  /**
   * Lists the buckets owned by the authenticated user (ListBuckets).
   *
   * @return the buckets and their owner
   */
  Future<S3ListBucketsResult> listBuckets();

  /**
   * Creates a bucket (CreateBucket) in the client's region. For any region other than
   * {@code us-east-1}, the request names the region as the bucket's location constraint.
   * <p>
   * Bucket names are checked before any request is sent: 3 to 63 lowercase letters, digits, dots
   * and hyphens, starting and ending with a letter or digit, no two adjacent dots, not an IP
   * address. With virtual-hosted-style access over HTTPS, names containing dots are rejected
   * (they don't match the server's wildcard certificate). An invalid name fails with an
   * {@link S3ClientException}. The same check applies to every method taking a bucket.
   *
   * @param bucket the bucket name
   * @return a future completed when the bucket exists; it fails with an
   *     {@link S3ServiceException} such as {@code BucketAlreadyOwnedByYou} or
   *     {@code BucketAlreadyExists} if the name is taken
   */
  Future<Void> createBucket(String bucket);

  /**
   * Checks that a bucket exists and is accessible (HeadBucket).
   * <p>
   * A HEAD response has no body, so failures carry no S3 error code: a missing bucket fails with
   * an {@link S3ServiceException} with status code 404, a bucket you can't access with 403.
   *
   * @param bucket the bucket name
   * @return a future completed if the bucket exists and is accessible
   */
  Future<Void> headBucket(String bucket);

  /**
   * Deletes an empty bucket (DeleteBucket).
   *
   * @param bucket the bucket name
   * @return a future completed when the bucket is deleted; it fails with an
   *     {@link S3ServiceException} such as {@code NoSuchBucket} or {@code BucketNotEmpty}
   */
  Future<Void> deleteBucket(String bucket);

  /**
   * Lists one page of the objects in a bucket (ListObjectsV2), in key order.
   * <p>
   * A page holds at most {@link S3ListObjectsOptions#getMaxKeys() maxKeys} entries (server
   * default 1000). If the result is {@linkplain S3ListObjectsResult#isTruncated() truncated},
   * call again with its {@linkplain S3ListObjectsResult#getNextContinuationToken() next
   * continuation token}; there is no paging helper. With a delimiter (usually {@code "/"}), keys
   * below the next delimiter are grouped into {@linkplain S3ListObjectsResult#getCommonPrefixes()
   * common prefixes}, i.e. "folders".
   * <p>
   * Keys and prefixes of any characters are returned decoded: the request always asks for
   * URL-encoded keys ({@code encoding-type=url}) and the client decodes them.
   *
   * @param bucket the bucket name
   * @param options prefix, delimiter, page size, continuation token, start key; {@code null} for none
   * @return one page of results; it fails with an {@link S3ServiceException} such as
   *     {@code NoSuchBucket}, and with an {@link S3ClientException} if the server reports a
   *     truncated page without a continuation token (it couldn't be paged)
   */
  Future<S3ListObjectsResult> listObjects(String bucket, @Nullable S3ListObjectsOptions options);

  /**
   * Uploads an object from a buffer (PutObject), replacing any existing object with that key.
   * <p>
   * Object keys are 1 to 1024 bytes of UTF-8 (any characters; they are encoded on the wire). An
   * invalid key fails with an {@link S3ClientException}, as does a {@code null} body. The same
   * key check applies to every method taking a key.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param body the content; an empty buffer creates an empty object
   * @return the object's ETag as sent by the server (with its double quotes), or {@code null} if
   *     the server sent none
   */
  Future<@Nullable String> putObject(String bucket, String key, Buffer body);

  /**
   * Uploads an object from a buffer (PutObject) with a content type and user metadata.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param body the content; an empty buffer creates an empty object
   * @param options the content type and user metadata; {@code null} for none
   * @return the object's ETag as sent by the server (with its double quotes), or {@code null}
   * @see #putObject(String, String, Buffer)
   */
  Future<@Nullable String> putObject(String bucket, String key, Buffer body, @Nullable S3PutObjectOptions options);

  /**
   * Downloads a <strong>small</strong> object into memory (GetObject).
   * <p>
   * The object must not be larger than {@link S3ClientOptions#getMaxBufferedObjectSize()}
   * (default 4 MiB). A larger object fails with an {@link S3ClientException} pointing to
   * {@link #getObjectStream(String, String)}: when the server announces the size, before any of
   * the body is read; otherwise as soon as the limit is crossed, and the download is abandoned.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return the content; it fails with an {@link S3ServiceException} such as {@code NoSuchKey}
   */
  Future<Buffer> getObject(String bucket, String key);

  /**
   * Downloads an object of any size as a stream (GetObject), never buffering it in memory.
   * <p>
   * The future completes when the response headers arrive. An error response fails it with an
   * {@link S3ServiceException} (e.g. {@code NoSuchKey}) before any body is streamed. On success,
   * the {@link S3ObjectStream} is paused, carries the object's metadata, and its handlers run on
   * the calling context. <strong>Read it to the end or {@linkplain S3ObjectStream#cancel()
   * cancel} it</strong>: until then it holds a pooled connection.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return the paused object stream
   */
  Future<S3ObjectStream> getObjectStream(String bucket, String key);

  /**
   * Downloads a byte range of an object as a stream (GetObject with {@code Range}).
   * <p>
   * {@code range} is a single HTTP byte range: {@code bytes=0-99} (the first 100 bytes),
   * {@code bytes=100-} (from byte 100 to the end) or {@code bytes=-100} (the last 100 bytes). A
   * malformed range fails with an {@link S3ClientException}; a range outside the object fails
   * with an {@link S3ServiceException} ({@code InvalidRange}, HTTP 416). The stream's
   * {@linkplain S3ObjectMetadata#getContentLength() content length} is the length of the range.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param range the byte range
   * @return the paused object stream
   * @see #getObjectStream(String, String)
   */
  Future<S3ObjectStream> getObjectStream(String bucket, String key, String range);

  /**
   * Reads an object's metadata without its content (HeadObject).
   * <p>
   * As with {@link #headBucket}, failures carry no S3 error code: a missing object fails with an
   * {@link S3ServiceException} with status code 404.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return the object's metadata
   */
  Future<S3ObjectMetadata> headObject(String bucket, String key);

  /**
   * Deletes an object (DeleteObject). Deleting a key that doesn't exist succeeds.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return a future completed when the object is deleted
   */
  Future<Void> deleteObject(String bucket, String key);

  /**
   * The presigner for this client's options: URLs point to the
   * {@linkplain S3ClientOptions#setPublicEndpoint public endpoint} when one is set. Always the same
   * instance; it holds no resources and keeps working after {@link #close()}.
   *
   * @return the presigner
   */
  @CacheReturn
  S3Presigner presigner();

  /**
   * Closes the client and releases its HTTP connections. Idempotent. Operations started after
   * {@code close()} fail with an {@link S3ClientException}.
   *
   * @return a future completed on the caller's context once the client is closed
   */
  Future<Void> close();
}
