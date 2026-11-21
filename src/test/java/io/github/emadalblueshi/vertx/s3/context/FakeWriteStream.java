package io.github.emadalblueshi.vertx.s3.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.streams.WriteStream;

/**
 * A {@link WriteStream} owned by a context, with a small write queue so that backpressure
 * ({@code writeQueueFull}/{@code drainHandler}) is exercised. Used to self-test
 * {@link CallerContext}. It is well-behaved by default; a {@link Fault} makes it break one rule.
 * Each write completes asynchronously on the owner context.
 */
final class FakeWriteStream<T> implements WriteStream<T> {

  enum Fault {
    NONE,
    /** Calls the drain handler on another context. */
    DRAIN_ON_OTHER_CONTEXT,
    /** Completes write Futures from a plain Java thread. */
    WRITE_FUTURE_FROM_PLAIN_THREAD
  }

  private final Context owner;
  private final Context other;
  private final Fault fault;
  private final List<T> written = Collections.synchronizedList(new ArrayList<>());

  private volatile int maxQueue = 2;
  private volatile int queued;
  private volatile int drains;
  private volatile Handler<Void> drainHandler;
  private volatile Promise<Void> endPromise;

  FakeWriteStream(Context owner, Context other, Fault fault) {
    this.owner = owner;
    this.other = other;
    this.fault = fault;
  }

  /** Items written so far, in order. */
  List<T> written() {
    return new ArrayList<>(written);
  }

  /** How many times the drain handler was called. */
  int drains() {
    return drains;
  }

  @Override
  public WriteStream<T> exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public Future<Void> write(T data) {
    Promise<Void> promise = Promise.promise();
    written.add(data);
    queued++;
    owner.runOnContext(v -> owner.owner().setTimer(5, id -> {
      queued--;
      completeWrite(promise);
      maybeDrain();
      maybeEnd();
    }));
    return promise.future();
  }

  @Override
  public Future<Void> end() {
    Promise<Void> promise = Promise.promise();
    endPromise = promise;
    owner.runOnContext(v -> maybeEnd());
    return promise.future();
  }

  @Override
  public WriteStream<T> setWriteQueueMaxSize(int maxSize) {
    this.maxQueue = maxSize;
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return queued >= maxQueue;
  }

  @Override
  public WriteStream<T> drainHandler(Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

  private void completeWrite(Promise<Void> promise) {
    if (fault == Fault.WRITE_FUTURE_FROM_PLAIN_THREAD) {
      Thread thread = new Thread(() -> promise.complete(), "fake-plain-completer");
      thread.setDaemon(true);
      thread.start();
    } else {
      promise.complete();
    }
  }

  private void maybeDrain() {
    Handler<Void> handler = drainHandler;
    if (handler != null && queued <= maxQueue / 2) {
      drains++;
      if (fault == Fault.DRAIN_ON_OTHER_CONTEXT) {
        other.runOnContext(v -> handler.handle(null));
      } else {
        handler.handle(null);
      }
    }
  }

  private void maybeEnd() {
    Promise<Void> promise = endPromise;
    if (promise != null && queued == 0) {
      promise.tryComplete();
    }
  }
}
