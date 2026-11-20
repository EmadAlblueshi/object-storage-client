package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Objects;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;

/**
 * The {@link S3Client} implementation.
 * <p>
 * Public only because {@link S3Client#create} instantiates it across packages (allowlisted in
 * {@code PackagePolicyTest}). It is internal: the module doesn't export this package.
 * <p>
 * Every operation captures the caller's context first and returns a Future bound to it (Vert.x
 * rule 3). After construction nothing throws to the caller.
 */
public final class S3ClientImpl implements S3Client {

  private final Vertx vertx;
  private final Transport transport;
  private final Runnable removeCloseHook;
  /** The shutdown Future, set by the first close (explicit or close hook); non-null means closed. */
  private volatile Future<Void> shutdown;

  public S3ClientImpl(Vertx vertx, S3ClientOptions options) {
    this.vertx = Objects.requireNonNull(vertx, "vertx");
    ClientConfig config = ClientConfig.from(options);
    this.transport = new Transport(vertx, config);
    this.removeCloseHook = ContextSupport.addCloseHook(vertx, this::closeFromHook);
  }

  @Override
  public Future<S3ListBucketsResult> listBuckets() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    if (shutdown != null) {
      return caller.failed(new S3ClientException("The S3 client is closed", null));
    }
    return caller.bind(transport.execute(caller, S3Request.service(HttpMethod.GET))
      .compose(response -> {
        try {
          return caller.succeeded(ResponseParsers.listBuckets(response.body));
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListBuckets response: " + e.getMessage(), e));
        }
      }));
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
