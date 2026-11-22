package io.github.emadalblueshi.vertx.s3.impl;

import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * A test source driven step by step: each {@link #emit}, {@link #end} or {@link #fail} delivers one
 * event on the source's own context, and {@code pause}/{@code resume} are recorded, so a test can
 * wait for the upload to pause or resume the source instead of relying on timing. Like a real
 * stream, the test should not emit while the source is paused.
 */
final class ScriptedReadStream implements ReadStream<Buffer> {

  private final Context context;
  private volatile Handler<Buffer> handler;
  private volatile Handler<Void> endHandler;
  private volatile Handler<Throwable> exceptionHandler;
  private volatile boolean paused;
  private volatile boolean handlerSet;
  final AtomicInteger pauses = new AtomicInteger();
  final AtomicInteger resumes = new AtomicInteger();
  private Promise<Void> nextPause = Promise.promise();
  private Promise<Void> nextResume = Promise.promise();

  ScriptedReadStream(Context context) {
    this.context = context;
  }

  @Override
  public synchronized ReadStream<Buffer> pause() {
    paused = true;
    pauses.incrementAndGet();
    nextPause.tryComplete();
    return this;
  }

  @Override
  public synchronized ReadStream<Buffer> resume() {
    paused = false;
    resumes.incrementAndGet();
    nextResume.tryComplete();
    return this;
  }

  @Override
  public ReadStream<Buffer> fetch(long amount) {
    return resume();
  }

  @Override
  public ReadStream<Buffer> handler(Handler<Buffer> handler) {
    this.handler = handler;
    handlerSet |= handler != null;
    return this;
  }

  @Override
  public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  boolean isPaused() {
    return paused;
  }

  boolean handlerSet() {
    return handlerSet;
  }

  /** Completes when the source is next paused (at once if it is paused now). */
  synchronized Future<Void> whenPaused() {
    if (paused) {
      return Future.succeededFuture();
    }
    nextPause = Promise.promise();
    return nextPause.future();
  }

  /** Completes when the source is next resumed (at once if it is flowing now). */
  synchronized Future<Void> whenResumed() {
    if (!paused) {
      return Future.succeededFuture();
    }
    nextResume = Promise.promise();
    return nextResume.future();
  }

  Future<Void> emit(String chunk) {
    return deliver(() -> handler.handle(Buffer.buffer(chunk)));
  }

  Future<Void> end() {
    return deliver(() -> endHandler.handle(null));
  }

  Future<Void> fail(Throwable cause) {
    return deliver(() -> exceptionHandler.handle(cause));
  }

  private Future<Void> deliver(Runnable event) {
    Promise<Void> delivered = Promise.promise();
    context.runOnContext(v -> {
      event.run();
      delivered.complete();
    });
    return delivered.future();
  }
}
