package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Objects;
import java.util.concurrent.Callable;

import io.vertx.core.Closeable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.PromiseInternal;

/**
 * The single place that uses Vert.x internal APIs ({@code io.vertx.core.internal}), as
 * allowed by AGENTS.md and enforced by {@code PackagePolicyTest}. Re-verify on every Vert.x
 * upgrade.
 * <ul>
 *   <li>{@link Caller}: the caller's context, captured at call time. Every Future the public API
 *   returns is created from it (Vert.x rule 3), so listeners always run on that context,
 *   regardless of when the Future completes or where the work finished.</li>
 *   <li>{@link #addCloseHook}: closes a client when the context it was created on closes (e.g.
 *   verticle undeploy), resolved exactly like Vert.x's own {@code HttpClient}.</li>
 * </ul>
 */
final class ContextSupport {

  private ContextSupport() {
  }

  /**
   * Captures the caller's context. On a non-Vert.x thread this is a new event-loop context,
   * as with any Vert.x API.
   */
  static Caller capture(Vertx vertx) {
    return new Caller((ContextInternal) vertx.getOrCreateContext());
  }

  /**
   * Registers {@code hook} to run when the current context closes. That is the deployment's
   * context inside a verticle (undeploy), otherwise the {@code Vertx} instance (close), the same
   * resolution Vert.x uses for its own clients.
   *
   * @return a handle that removes the hook again (call it on explicit close)
   */
  static Runnable addCloseHook(Vertx vertx, Closeable hook) {
    Objects.requireNonNull(hook, "hook");
    ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
    context.addCloseHook(hook);
    return () -> context.removeCloseHook(hook);
  }

  /** The caller's context. All Futures handed back to the caller are created through it. */
  static final class Caller {

    private final ContextInternal context;

    private Caller(ContextInternal context) {
      this.context = context;
    }

    /** A Future completed on the caller's context with the outcome of {@code future}, wherever that completes. */
    <T> Future<T> bind(Future<T> future) {
      PromiseInternal<T> promise = context.promise();
      future.onComplete(promise);
      return promise.future();
    }

    /** An already-succeeded Future bound to the caller's context. */
    <T> Future<T> succeeded(T value) {
      return context.succeededFuture(value);
    }

    /** An already-failed Future bound to the caller's context. */
    <T> Future<T> failed(Throwable cause) {
      return context.failedFuture(cause);
    }

    /** Runs {@code task} on a worker thread (unordered); the Future is bound to the caller's context. */
    <T> Future<T> executeBlocking(Callable<T> task) {
      return context.executeBlocking(task, false);
    }
  }
}
