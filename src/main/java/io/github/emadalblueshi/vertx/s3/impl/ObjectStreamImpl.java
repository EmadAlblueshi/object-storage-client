package io.github.emadalblueshi.vertx.s3.impl;

import java.util.stream.Collector;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;

/**
 * {@link S3ObjectStream} over a paused {@link HttpClientResponse}.
 * <p>
 * Its own handlers are attached to the response at construction: Vert.x drops body chunks that
 * arrive while no handler is set, and refuses handlers once the response has ended. Events are
 * forwarded to the user's handlers on the response's context, which is the context that called
 * {@code getObjectStream} (the transport issues the request there). Backpressure
 * ({@code pause}/{@code resume}/{@code fetch}) goes straight to the response.
 * <p>
 * At most one terminal event is delivered: end, failure, or the cancellation failure.
 */
final class ObjectStreamImpl implements S3ObjectStream {

  private final Vertx vertx;
  private final ContextSupport.Caller owner;
  private final HttpClientResponse response;
  private final S3ObjectMetadata metadata;
  private Handler<Buffer> handler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  /** A terminal event was delivered (end, failure or cancellation). */
  private boolean done;
  private boolean cancelled;

  ObjectStreamImpl(Vertx vertx, ContextSupport.Caller owner, HttpClientResponse response, S3ObjectMetadata metadata) {
    this.vertx = vertx;
    this.owner = owner;
    this.response = response;
    this.metadata = metadata;
    response.handler(this::handleData);
    response.endHandler(this::handleEnd);
    response.exceptionHandler(this::handleFailure);
  }

  @Override
  public S3ObjectMetadata metadata() {
    return metadata;
  }

  @Override
  public Future<Void> cancel() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    synchronized (this) {
      if (done || cancelled) {
        return caller.succeeded(null);
      }
      cancelled = true;
    }
    // Delivered later on the stream's context, never from inside cancel()
    owner.runOnContext(() -> fail(cancelledException()));
    // Closes the connection if the response is still in flight; resuming drains anything already
    // received, so the connection is released in every case. Our handlers ignore what follows.
    Future<Boolean> cancelling = response.request().cancel();
    response.resume();
    return caller.bind(cancelling.<Void>mapEmpty()
      .recover(e -> caller.failed(new S3ClientException("Cancelling the object stream failed: " + e.getMessage(), e))));
  }

  @Override
  public synchronized S3ObjectStream handler(Handler<Buffer> handler) {
    this.handler = handler;
    return this;
  }

  @Override
  public synchronized S3ObjectStream endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public synchronized S3ObjectStream exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  /** Like {@code pipeTo}, consumes the whole stream, so it also resumes it (it is handed out paused). */
  @Override
  public <R, A> Future<R> collect(Collector<Buffer, A, R> collector) {
    Future<R> result = S3ObjectStream.super.collect(collector);
    resume();
    return result;
  }

  @Override
  public S3ObjectStream pause() {
    response.pause();
    return this;
  }

  @Override
  public S3ObjectStream resume() {
    response.resume();
    return this;
  }

  @Override
  public S3ObjectStream fetch(long amount) {
    response.fetch(amount);
    return this;
  }

  private void handleData(Buffer chunk) {
    Handler<Buffer> h;
    synchronized (this) {
      if (done || cancelled) {
        return;
      }
      h = handler;
    }
    if (h != null) {
      h.handle(chunk);
    }
  }

  private void handleEnd(Void v) {
    Handler<Void> h;
    synchronized (this) {
      if (done || cancelled) {
        return;
      }
      done = true;
      h = endHandler;
    }
    if (h != null) {
      h.handle(null);
    }
  }

  private void handleFailure(Throwable error) {
    boolean wasCancelled;
    synchronized (this) {
      wasCancelled = cancelled;
    }
    fail(wasCancelled ? cancelledException() : new S3ClientException("The object stream failed: " + error.getMessage(), error));
  }

  private void fail(S3ClientException error) {
    Handler<Throwable> h;
    synchronized (this) {
      if (done) {
        return;
      }
      done = true;
      h = exceptionHandler;
    }
    if (h != null) {
      h.handle(error);
    }
  }

  private static S3ClientException cancelledException() {
    return new S3ClientException("The object stream was cancelled", null);
  }
}
