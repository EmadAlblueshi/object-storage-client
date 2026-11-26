package io.github.emadalblueshi.vertx.s3.context;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.opentest4j.AssertionFailedError;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

/**
 * Context-correctness harness: runs an operation <em>as</em> a given kind of caller and checks
 * that every callback lands on the caller's own context.
 * <p>
 * For {@link CallerKind#EVENT_LOOP}, {@link CallerKind#WORKER} and
 * {@link CallerKind#VIRTUAL_THREAD}, a verticle with that threading model is deployed. The
 * operation is invoked on its context, {@link Vertx#currentContext()} is captured before the
 * call, and every Future completion and stream handler must observe the <strong>same context
 * instance</strong>. Being on "a" Vert.x thread is not enough.
 * <p>
 * For {@link CallerKind#NON_VERTX_THREAD}, the operation is invoked from a plain Java thread. It
 * must complete within the timeout, and stream handlers must all fire on one Vert.x context.
 * <p>
 * Every {@code assert*} method returns a Future that is completed with the operation's outcome
 * when all checks pass, or failed with an {@link AssertionFailedError} on a violation or timeout.
 * Failures of the operation itself are propagated unchanged, so tests can assert on them.
 * Operations must never throw synchronously (a throw counts as a violation).
 * <p>
 * Limitation: if the returned Future is already complete when the harness attaches its listener,
 * the listener runs synchronously on the caller's thread and the check passes. That is correct
 * from the caller's point of view, but a wrong implementation that happens to win this race goes
 * unnoticed. Real operations perform I/O, so they complete after they return. Self-tests that
 * simulate wrong implementations must defer the wrong completion until after the current caller
 * task has finished.
 * <p>
 * This is why AGENTS.md (Vert.x rule 3) requires every Future returned by the public API to come
 * from a promise bound to the caller's context. With that rule, correctness doesn't depend on
 * timing. The harness complements the rule but can't prove it.
 */
public final class CallerContext {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  private final Vertx vertx;
  private final CallerKind kind;
  private final Context context;
  private final String deploymentId;
  private final Duration timeout;

  private CallerContext(Vertx vertx, CallerKind kind, Context context, String deploymentId, Duration timeout) {
    this.vertx = vertx;
    this.kind = kind;
    this.context = context;
    this.deploymentId = deploymentId;
    this.timeout = timeout;
  }

  /**
   * Prepares a caller of the given kind. For verticle kinds this deploys a verticle with the
   * matching threading model. Pair it with {@link #undeploy()}.
   */
  public static Future<CallerContext> deploy(Vertx vertx, CallerKind kind) {
    Objects.requireNonNull(vertx, "vertx");
    Objects.requireNonNull(kind, "kind");
    if (kind == CallerKind.NON_VERTX_THREAD) {
      return Future.succeededFuture(new CallerContext(vertx, kind, null, null, DEFAULT_TIMEOUT));
    }
    CallerVerticle verticle = new CallerVerticle();
    return vertx
      .deployVerticle(verticle, new DeploymentOptions().setThreadingModel(kind.threadingModel()))
      .map(id -> new CallerContext(vertx, kind, verticle.callerContext, id, DEFAULT_TIMEOUT));
  }

  /** Undeploys the caller verticle (no-op for {@link CallerKind#NON_VERTX_THREAD}). */
  public Future<Void> undeploy() {
    return deploymentId == null ? Future.succeededFuture() : vertx.undeploy(deploymentId);
  }

  /** A copy of this caller that uses a different timeout for its checks. */
  public CallerContext withTimeout(Duration timeout) {
    return new CallerContext(vertx, kind, context, deploymentId, Objects.requireNonNull(timeout, "timeout"));
  }

  public CallerKind kind() {
    return kind;
  }

  /** The caller verticle's context, or {@code null} for {@link CallerKind#NON_VERTX_THREAD}. */
  public Context context() {
    return context;
  }

  /**
   * Invokes {@code op} as this caller and checks that the returned Future completes on the
   * caller's context.
   *
   * @param op receives the caller context ({@code null} for a non-Vert.x thread) and starts the
   *     operation under test, e.g. {@code ctx -> client.headObject(...)}
   * @return the operation's outcome once the checks pass
   */
  public <T> Future<T> assertCompletesOnCallerContext(Function<Context, Future<T>> op) {
    Check<T> check = new Check<>("Future completion");
    runAsCaller(check, callerCtx -> {
      Future<T> future = invoke(check, op, callerCtx);
      if (future != null) {
        future.onComplete(ar -> {
          if (check.verifyFuture("Future completion", callerCtx)) {
            check.complete(ar);
          }
        });
      }
    });
    return check.future();
  }

  /**
   * Invokes {@code op} as this caller, then consumes the returned {@link ReadStream} with explicit
   * backpressure ({@code pause()}, then {@code fetch(1)} per item, each fetch issued from a timer
   * on the handler's context). Checks that:
   * <ul>
   *   <li>the Future returning the stream completes on the caller's context;</li>
   *   <li>{@code handler}, {@code endHandler} and {@code exceptionHandler} fire on the caller's
   *   context;</li>
   *   <li>no item is delivered without outstanding demand (no emission while paused).</li>
   * </ul>
   *
   * @return the items received, in order, once the stream has ended
   */
  public <T> Future<List<T>> assertReadStreamOnCallerContext(Function<Context, Future<? extends ReadStream<T>>> op) {
    Check<List<T>> check = new Check<>("ReadStream");
    runAsCaller(check, callerCtx -> {
      Future<? extends ReadStream<T>> future = invoke(check, op, callerCtx);
      if (future == null) {
        return;
      }
      future.onComplete(ar -> {
        if (!check.verifyFuture("Future completion (stream)", callerCtx)) {
          return;
        }
        if (ar.failed()) {
          check.fail(ar.cause());
          return;
        }
        ReadStream<T> stream = ar.result();
        List<T> items = Collections.synchronizedList(new ArrayList<>());
        AtomicLong demand = new AtomicLong();
        stream.pause();
        stream.exceptionHandler(err -> {
          if (check.verifyStreamCallback("exceptionHandler", callerCtx)) {
            check.fail(err);
          }
        });
        stream.endHandler(v -> {
          if (check.verifyStreamCallback("endHandler", callerCtx)) {
            check.succeed(new ArrayList<>(items));
          }
        });
        stream.handler(item -> {
          if (!check.verifyStreamCallback("handler", callerCtx)) {
            return;
          }
          if (demand.getAndDecrement() <= 0) {
            check.violation("handler fired without outstanding demand (emitted while paused)");
            return;
          }
          items.add(item);
          vertx.setTimer(1, id -> {
            demand.incrementAndGet();
            stream.fetch(1);
          });
        });
        demand.incrementAndGet();
        stream.fetch(1);
      });
    });
    return check.future();
  }

  /**
   * Invokes {@code op} as this caller, then writes {@code items} to the returned
   * {@link WriteStream} honouring backpressure ({@code writeQueueFull()}, {@code drainHandler})
   * and finally calls {@code end()}. Checks that the Future returning the stream, every write
   * Future, the {@code drainHandler}, the {@code exceptionHandler} and the {@code end()} Future
   * all run on the caller's context.
   *
   * @return completes once {@code end()} has completed and every write Future has completed
   */
  public <T> Future<Void> assertWriteStreamOnCallerContext(
    Function<Context, Future<? extends WriteStream<T>>> op, List<T> items) {
    Objects.requireNonNull(items, "items");
    Check<Void> check = new Check<>("WriteStream");
    runAsCaller(check, callerCtx -> {
      Future<? extends WriteStream<T>> future = invoke(check, op, callerCtx);
      if (future == null) {
        return;
      }
      future.onComplete(ar -> {
        if (!check.verifyFuture("Future completion (stream)", callerCtx)) {
          return;
        }
        if (ar.failed()) {
          check.fail(ar.cause());
          return;
        }
        WriteStream<T> stream = ar.result();
        stream.exceptionHandler(err -> {
          if (check.verifyStreamCallback("exceptionHandler", callerCtx)) {
            check.fail(err);
          }
        });
        new Writer<>(check, stream, items, callerCtx).writeFrom(0);
      });
    });
    return check.future();
  }

  // ---------------------------------------------------------------------------------------------

  private final class Writer<T> {
    private final Check<Void> check;
    private final WriteStream<T> stream;
    private final List<T> items;
    private final Context callerCtx;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();

    Writer(Check<Void> check, WriteStream<T> stream, List<T> items, Context callerCtx) {
      this.check = check;
      this.stream = stream;
      this.items = items;
      this.callerCtx = callerCtx;
    }

    void writeFrom(int index) {
      int i = index;
      while (i < items.size() && !stream.writeQueueFull()) {
        pendingWrites.incrementAndGet();
        stream.write(items.get(i++)).onComplete(war -> {
          if (!check.verifyStreamCallback("write Future", callerCtx)) {
            return;
          }
          if (war.failed()) {
            check.fail(war.cause());
            return;
          }
          pendingWrites.decrementAndGet();
          maybeDone();
        });
      }
      if (i < items.size()) {
        int next = i;
        stream.drainHandler(v -> {
          if (!check.verifyStreamCallback("drainHandler", callerCtx)) {
            return;
          }
          stream.drainHandler(null);
          writeFrom(next);
        });
        return;
      }
      stream.end().onComplete(ear -> {
        if (!check.verifyStreamCallback("end() Future", callerCtx)) {
          return;
        }
        if (ear.failed()) {
          check.fail(ear.cause());
          return;
        }
        ended.set(true);
        maybeDone();
      });
    }

    private void maybeDone() {
      if (ended.get() && pendingWrites.get() == 0) {
        check.succeed(null);
      }
    }
  }

  /** Runs the task as the caller: on the caller verticle's context, or on a fresh plain thread. */
  private void runAsCaller(Check<?> check, Consumer<Context> task) {
    if (kind == CallerKind.NON_VERTX_THREAD) {
      Thread thread = new Thread(() -> {
        if (Vertx.currentContext() != null) {
          check.violation("harness error: non-Vert.x caller thread has a context");
          return;
        }
        task.accept(null);
      }, "non-vertx-caller");
      thread.setDaemon(true);
      thread.start();
    } else {
      context.runOnContext(v -> {
        if (Vertx.currentContext() != context) {
          check.violation("harness error: not running on the caller verticle's context");
          return;
        }
        task.accept(context);
      });
    }
  }

  private static <R> R invoke(Check<?> check, Function<Context, R> op, Context callerCtx) {
    try {
      R result = op.apply(callerCtx);
      if (result == null) {
        check.violation("operation returned null instead of a Future");
      }
      return result;
    } catch (Throwable t) {
      check.violation("operation threw synchronously (async methods must return a failed Future): " + t, t);
      return null;
    }
  }

  private static String describe(Context ctx) {
    if (ctx == null) {
      return "no Vert.x context";
    }
    return ctx.threadingModel() + " context@" + Integer.toHexString(System.identityHashCode(ctx));
  }

  /** One harness check: a timeout, a context rule, and the resulting Future. */
  private final class Check<T> {
    private final String what;
    private final Promise<T> promise = Promise.promise();
    private final long timerId;
    // For NON_VERTX_THREAD: the first Vert.x context a stream handler fired on.
    private final AtomicReference<Context> observed = new AtomicReference<>();

    Check(String what) {
      this.what = what;
      this.timerId = vertx.setTimer(timeout.toMillis(), id -> violation("did not complete within " + timeout));
    }

    Future<T> future() {
      return promise.future();
    }

    /**
     * Rule for the Future returned by the operation. Returns {@code true} if it holds; otherwise
     * fails the check and returns {@code false}. A non-Vert.x caller has no context to return to,
     * so its Future may complete on any thread.
     */
    boolean verifyFuture(String callback, Context callerCtx) {
      return kind == CallerKind.NON_VERTX_THREAD || verifyCallerContext(callback, callerCtx);
    }

    /**
     * Rule for stream callbacks (handlers, write/end Futures). Returns {@code true} if it holds;
     * otherwise fails the check and returns {@code false}. For a non-Vert.x caller, every stream
     * callback must fire on one and the same Vert.x context.
     */
    boolean verifyStreamCallback(String callback, Context callerCtx) {
      if (kind != CallerKind.NON_VERTX_THREAD) {
        return verifyCallerContext(callback, callerCtx);
      }
      Context actual = Vertx.currentContext();
      if (actual == null) {
        violation(callback + " ran without a Vert.x context (thread " + Thread.currentThread().getName() + ")");
        return false;
      }
      Context first = observed.updateAndGet(prev -> prev == null ? actual : prev);
      if (first != actual) {
        violation(callback + " ran on " + describe(actual) + ", but earlier callbacks ran on " + describe(first));
        return false;
      }
      return true;
    }

    private boolean verifyCallerContext(String callback, Context callerCtx) {
      Context actual = Vertx.currentContext();
      if (actual != callerCtx) {
        violation(callback + " ran on " + describe(actual) + " (thread " + Thread.currentThread().getName()
          + "), expected the caller's " + describe(callerCtx));
        return false;
      }
      return true;
    }

    void complete(AsyncResult<T> ar) {
      if (ar.succeeded()) {
        succeed(ar.result());
      } else {
        fail(ar.cause());
      }
    }

    void succeed(T value) {
      vertx.cancelTimer(timerId);
      promise.tryComplete(value);
    }

    void fail(Throwable cause) {
      vertx.cancelTimer(timerId);
      promise.tryFail(cause);
    }

    void violation(String message) {
      violation(message, null);
    }

    void violation(String message, Throwable cause) {
      vertx.cancelTimer(timerId);
      promise.tryFail(new AssertionFailedError("[" + kind + "] " + what + ": " + message, cause));
    }
  }

  /** Captures its own context when deployed. */
  private static final class CallerVerticle extends VerticleBase {
    volatile Context callerContext;

    @Override
    public Future<?> start() {
      callerContext = Vertx.currentContext();
      return Future.succeededFuture();
    }
  }
}
