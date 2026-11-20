package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Objects;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;

/**
 * The {@link S3Client} implementation.
 * <p>
 * Public only because {@link S3Client#create} instantiates it across packages (allowlisted in
 * {@code PackagePolicyTest}). It is internal: the module doesn't export this package.
 * <p>
 * Every operation captures the caller's context first and returns a Future bound to it (Vert.x
 * rule 3). After construction nothing throws to the caller: a closed client and invalid
 * arguments are reported as failed Futures, before any I/O.
 */
public final class S3ClientImpl implements S3Client {

  /** AWS rejects a {@code LocationConstraint} for this region: it is the default. */
  private static final String US_EAST_1 = "us-east-1";

  private final Vertx vertx;
  private final ClientConfig config;
  private final Transport transport;
  private final Runnable removeCloseHook;
  /** The shutdown Future, set by the first close (explicit or close hook); non-null means closed. */
  private volatile Future<Void> shutdown;

  public S3ClientImpl(Vertx vertx, S3ClientOptions options) {
    this.vertx = Objects.requireNonNull(vertx, "vertx");
    this.config = ClientConfig.from(options);
    this.transport = new Transport(vertx, config);
    this.removeCloseHook = ContextSupport.addCloseHook(vertx, this::closeFromHook);
  }

  @Override
  public Future<S3ListBucketsResult> listBuckets() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return send(caller, S3Request.service(HttpMethod.GET))
      .compose(response -> {
        try {
          return caller.succeeded(ResponseParsers.listBuckets(response.body));
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListBuckets response: " + e.getMessage(), e));
        }
      });
  }

  @Override
  public Future<Void> createBucket(String bucket) {
    return bucketRequest(HttpMethod.PUT, bucket, createBucketBody(config.region));
  }

  @Override
  public Future<Void> headBucket(String bucket) {
    return bucketRequest(HttpMethod.HEAD, bucket, null);
  }

  @Override
  public Future<Void> deleteBucket(String bucket) {
    return bucketRequest(HttpMethod.DELETE, bucket, null);
  }

  @Override
  public Future<Void> close() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    removeCloseHook.run();
    return caller.bind(shutdown());
  }

  /** Whether the client is closed (explicitly or by the close hook). For tests. */
  boolean isClosed() {
    return shutdown != null;
  }

  /**
   * The CreateBucket body: a {@code LocationConstraint} naming the client's region, or none for
   * {@code us-east-1}. Package-private for tests.
   */
  static Buffer createBucketBody(String region) {
    if (US_EAST_1.equals(region)) {
      return null;
    }
    return new XmlWriter("CreateBucketConfiguration", S3Xml.S3_NAMESPACE)
      .element("LocationConstraint", region)
      .toBuffer();
  }

  private Future<Void> bucketRequest(HttpMethod method, String bucket, Buffer body) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = BucketNames.problem(bucket, config);
    if (problem != null) {
      return caller.failed(new S3ClientException(problem, null));
    }
    return send(caller, S3Request.bucket(method, bucket, body)).mapEmpty();
  }

  /** Sends {@code request} unless the client is closed; the result is bound to the caller's context. */
  private Future<S3Response> send(ContextSupport.Caller caller, S3Request request) {
    if (shutdown != null) {
      return caller.failed(new S3ClientException("The S3 client is closed", null));
    }
    return caller.bind(transport.execute(caller, request));
  }

  /** Called by Vert.x when the creating context closes (verticle undeploy or Vert.x close). */
  private void closeFromHook(Completable<Void> completion) {
    shutdown().onComplete(completion);
  }

  /** Closes the transport exactly once; every caller gets the same shutdown Future. */
  private Future<Void> shutdown() {
    Future<Void> current = shutdown;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (shutdown == null) {
        shutdown = transport.close();
      }
      return shutdown;
    }
  }
}
