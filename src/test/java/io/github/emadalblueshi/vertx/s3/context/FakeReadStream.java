package io.github.emadalblueshi.vertx.s3.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

/**
 * A {@link ReadStream} owned by a context, used to self-test {@link CallerContext}. It is
 * well-behaved by default (emits on its owner context, honours {@code pause}/{@code fetch}); a
 * {@link Fault} makes it break one rule.
 */
final class FakeReadStream<T> implements ReadStream<T> {

  enum Fault {
    NONE,
    /** Delivers items from a plain Java thread. */
    EMIT_FROM_PLAIN_THREAD,
    /** Delivers the end event on another context. */
    END_ON_OTHER_CONTEXT,
    /** Emits everything at once, ignoring pause/fetch demand. */
    IGNORE_PAUSE
  }

  private final Context owner;
  private final Context other;
  private final Fault fault;
  private final Deque<T> items;

  private volatile Handler<T> handler;
  private volatile Handler<Void> endHandler;
  private volatile long demand = Long.MAX_VALUE;
  private volatile boolean ended;

  FakeReadStream(Context owner, Context other, Fault fault, List<T> items) {
    this.owner = owner;
    this.other = other;
    this.fault = fault;
    this.items = new ArrayDeque<>(items);
  }

  @Override
  public ReadStream<T> exceptionHandler(Handler<Throwable> handler) {
    return this;
  }

  @Override
  public ReadStream<T> handler(Handler<T> handler) {
    this.handler = handler;
    if (handler != null) {
      schedule();
    }
    return this;
  }

  @Override
  public ReadStream<T> pause() {
    demand = 0;
    return this;
  }

  @Override
  public ReadStream<T> resume() {
    return fetch(Long.MAX_VALUE);
  }

  @Override
  public ReadStream<T> fetch(long amount) {
    long d = demand + amount;
    demand = d < 0 ? Long.MAX_VALUE : d;
    schedule();
    return this;
  }

  @Override
  public ReadStream<T> endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  private void schedule() {
    owner.runOnContext(v -> drain());
  }

  private void drain() {
    Handler<T> h = handler;
    if (h == null || ended) {
      return;
    }
    while (!items.isEmpty() && (demand > 0 || fault == Fault.IGNORE_PAUSE)) {
      if (demand != Long.MAX_VALUE && demand > 0) {
        demand--;
      }
      deliver(h, items.poll());
    }
    if (items.isEmpty() && !ended) {
      ended = true;
      Handler<Void> end = endHandler;
      if (end != null) {
        if (fault == Fault.END_ON_OTHER_CONTEXT) {
          other.runOnContext(v -> end.handle(null));
        } else {
          end.handle(null);
        }
      }
    }
  }

  private void deliver(Handler<T> h, T item) {
    if (fault == Fault.EMIT_FROM_PLAIN_THREAD) {
      Thread thread = new Thread(() -> h.handle(item), "fake-plain-emitter");
      thread.setDaemon(true);
      thread.start();
    } else {
      h.handle(item);
    }
  }
}
