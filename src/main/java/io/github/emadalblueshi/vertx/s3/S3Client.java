package io.github.emadalblueshi.vertx.s3;

import io.github.emadalblueshi.vertx.s3.impl.S3ClientImpl;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

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
 * response, credentials, closed client). Methods never throw; failures are reported through the
 * returned Future.
 * <p>
 * A client is thread-safe and can be shared between verticles. It is bound to the {@code Vertx}
 * it was created with. Created inside a verticle, it is closed automatically when that verticle
 * is undeployed, like a Vert.x {@code HttpClient}. Otherwise it is closed with its {@code Vertx}.
 * Don't use a client after its {@code Vertx} is closed: Vert.x can no longer deliver results.
 */
public interface S3Client {

  /**
   * Creates a client.
   *
   * @param vertx the Vert.x instance the client is bound to
   * @param options the options (copied; later changes have no effect)
   * @return the client
   * @throws IllegalArgumentException if the options are invalid, e.g. a missing endpoint, region
   *     or credentials, or {@code UNSIGNED} payload signing over {@code http://}
   * @throws NullPointerException if {@code vertx} or {@code options} is {@code null}
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
   * Closes the client and releases its HTTP connections. Idempotent. Operations started after
   * {@code close()} fail with an {@link S3ClientException}.
   *
   * @return a future completed on the caller's context once the client is closed
   */
  Future<Void> close();
}
