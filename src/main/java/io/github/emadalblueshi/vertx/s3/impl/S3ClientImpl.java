package io.github.emadalblueshi.vertx.s3.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsResult;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
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
    return call(caller, null, () -> transport.execute(caller, S3Request.service(HttpMethod.GET)))
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
  public Future<S3ListObjectsResult> listObjects(String bucket, S3ListObjectsOptions options) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = BucketNames.problem(bucket, config);
    if (problem == null) {
      problem = ObjectRequests.listProblem(options);
    }
    return call(caller, problem, () -> transport.execute(caller,
        new S3Request(HttpMethod.GET, bucket, null, ObjectRequests.listQuery(options), List.of(), null)))
      .compose(response -> {
        S3ListObjectsResult result;
        try {
          result = ResponseParsers.listObjects(response.body);
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListObjectsV2 response: " + e.getMessage(), e));
        }
        if (result.isTruncated() && result.getNextContinuationToken() == null) {
          // Returned as is, a "while (truncated)" paging loop would restart from page 1 forever
          return caller.failed(new S3ClientException("ListObjectsV2 response is truncated but has no continuation token", null));
        }
        return caller.succeeded(result);
      });
  }

  @Override
  public Future<String> putObject(String bucket, String key, Buffer body) {
    return putObject(bucket, key, body, null);
  }

  @Override
  public Future<String> putObject(String bucket, String key, Buffer body, S3PutObjectOptions options) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = objectProblem(bucket, key);
    List<Map.Entry<String, String>> headers = null;
    if (problem == null && body == null) {
      problem = "The body is required (use an empty Buffer for an empty object)";
    }
    if (problem == null) {
      try {
        headers = ObjectRequests.putHeaders(options);
      } catch (IllegalArgumentException e) {
        problem = e.getMessage();
      }
    }
    List<Map.Entry<String, String>> putHeaders = headers;
    return call(caller, problem, () -> transport.execute(caller,
        S3Request.object(HttpMethod.PUT, bucket, key, putHeaders, body)))
      .map(response -> response.headers.get(HttpHeaders.ETAG));
  }

  @Override
  public Future<Buffer> getObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    int limit = config.maxBufferedObjectSize;
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.GET, bucket, key, List.of(), null), limit,
        size -> new S3ClientException("The object is larger than the getObject limit of " + limit + " bytes"
          + (size >= 0 ? " (" + size + " bytes)" : "") + ": use getObjectStream, or raise "
          + "S3ClientOptions.maxBufferedObjectSize", null)))
      .map(response -> response.body);
  }

  @Override
  public Future<S3ObjectStream> getObjectStream(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return objectStream(caller, bucket, key, objectProblem(bucket, key), List.of());
  }

  @Override
  public Future<S3ObjectStream> getObjectStream(String bucket, String key, String range) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = objectProblem(bucket, key);
    if (problem == null) {
      problem = ObjectRequests.rangeProblem(range);
    }
    return objectStream(caller, bucket, key, problem, List.of(Map.entry("Range", String.valueOf(range))));
  }

  @Override
  public Future<S3ObjectMetadata> headObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.HEAD, bucket, key, List.of(), null)))
      .map(response -> ObjectRequests.metadata(response.headers));
  }

  @Override
  public Future<Void> deleteObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.DELETE, bucket, key, List.of(), null)))
      .mapEmpty();
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
    return call(caller, BucketNames.problem(bucket, config),
      () -> transport.execute(caller, S3Request.bucket(method, bucket, body))).mapEmpty();
  }

  private Future<S3ObjectStream> objectStream(ContextSupport.Caller caller, String bucket, String key, String problem,
                                              List<Map.Entry<String, String>> headers) {
    return call(caller, problem, () -> transport.stream(caller, S3Request.object(HttpMethod.GET, bucket, key, headers, null))
      .map(response -> new ObjectStreamImpl(vertx, caller, response, ObjectRequests.metadata(response.headers()))));
  }

  private String objectProblem(String bucket, String key) {
    String problem = BucketNames.problem(bucket, config);
    return problem != null ? problem : ObjectRequests.keyProblem(key);
  }

  /**
   * Runs {@code operation} unless the client is closed or {@code problem} (an argument check)
   * is set; either way the result is bound to the caller's context.
   */
  private <T> Future<T> call(ContextSupport.Caller caller, String problem, Supplier<Future<T>> operation) {
    if (shutdown != null) {
      return caller.failed(new S3ClientException("The S3 client is closed", null));
    }
    if (problem != null) {
      return caller.failed(new S3ClientException(problem, null));
    }
    return caller.bind(operation.get());
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
