package io.github.emadalblueshi.vertx.s3.impl;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.CloseableResource;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.PromiseInternal;
import io.vertx.core.internal.VertxInternal;

/**
 * The single place that uses Vert.x internal APIs ({@code io.vertx.core.internal}), as
 * allowed by AGENTS.md and enforced by {@code PackagePolicyTest}. Re-verify on every Vert.x
 * upgrade.
 * <ul>
 *   <li>{@link Caller}: the caller's context, captured at call time. Every Future the public API
 *   returns is created from it (Vert.x rule 3), so listeners always run on that context,
 *   regardless of when the Future completes or where the work finished.</li>
 *   <li>{@link #registerResource}: closes a client when the context it was created on closes
 *   (e.g. verticle undeploy), registered exactly like Vert.x's own {@code HttpClient}
 *   ({@code VertxInternal.registerResource}, which replaces the deprecated
 *   {@code ContextInternal.addCloseHook}).</li>
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
   * Registers a resource that is shut down when its owner closes: the current context's close
   * future inside a verticle (undeploy), otherwise the {@code Vertx} instance's (close). This is
   * how Vert.x 5.2.3 registers its own {@code HttpClient}.
   *
   * @param shutdown shuts the resource down; called at most once through the registration
   * @return the registration; {@link Registration#close()} unregisters and shuts down
   * @throws IllegalStateException if the owner is already closed (e.g. the context of an
   *     undeployed verticle; on a closed {@code Vertx}, creating the HTTP client already fails)
   */
  static Registration registerResource(Vertx vertx, Supplier<Future<Void>> shutdown) {
    Objects.requireNonNull(shutdown, "shutdown");
    io.vertx.core.internal.Closeable resource = timeout -> shutdown.get();
    CloseableResource<io.vertx.core.internal.Closeable> hook = ((VertxInternal) vertx).registerResource(resource);
    if (hook == null) {
      throw new IllegalStateException("The Vert.x instance or the creating context is already closed");
    }
    return new Registration(hook);
  }

  /** A resource registered with its owner's close future. */
  static final class Registration {

    private final CloseableResource<io.vertx.core.internal.Closeable> hook;

    private Registration(CloseableResource<io.vertx.core.internal.Closeable> hook) {
      this.hook = hook;
    }

    /**
     * Unregisters the resource from its owner, so the owner no longer references it, and shuts
     * it down. Idempotent; the owner closing first is fine too (the shutdown is memoized).
     */
    Future<Void> close() {
      return hook.shutdown(Duration.ZERO);
    }

    /** The object registered with the owner's close future. For tests. */
    Object registered() {
      return hook;
    }
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

    /** A promise bound to the caller's context, for results assembled from stream events. */
    <T> Promise<T> promise() {
      return context.promise();
    }

    /**
     * Runs {@code task} on the caller's context: directly when already running on it, otherwise
     * dispatched to it. Needed where Vert.x binds work to the <em>current</em> context, e.g.
     * {@code HttpClient.request()}: the response's stream handlers then run on the caller's
     * context (Vert.x rule 4), also for callers on non-Vert.x threads.
     */
    <T> Future<T> onContext(Supplier<Future<T>> task) {
      if (context.isRunningOnContext()) {
        return task.get();
      }
      PromiseInternal<T> promise = context.promise();
      context.runOnContext(v -> {
        try {
          task.get().onComplete(promise);
        } catch (RuntimeException e) {
          promise.tryFail(e);
        }
      });
      return promise.future();
    }

    /** Runs {@code action} later on the caller's context (never synchronously). */
    void runOnContext(Runnable action) {
      context.runOnContext(v -> action.run());
    }
  }
}
