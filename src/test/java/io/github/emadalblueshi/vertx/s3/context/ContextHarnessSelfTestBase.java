package io.github.emadalblueshi.vertx.s3.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.AssertionFailedError;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Self-tests for {@link CallerContext}: well-behaved operations must pass, and deliberately
 * wrong ones must make the harness fail. One subclass per {@link CallerKind}.
 * <p>
 * Later phases follow the same pattern for every public operation: an abstract test class that
 * uses {@link #caller} plus one tiny subclass per caller kind (the virtual-thread one tagged
 * {@code virtual-threads}).
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ContextHarnessSelfTestBase {

  protected Vertx vertx;
  protected CallerContext caller;

  protected abstract CallerKind kind();

  @BeforeAll
  void deployCaller(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    CallerContext.deploy(vertx, kind()).onComplete(testContext.succeeding(c -> {
      caller = c;
      testContext.completeNow();
    }));
  }

  @AfterAll
  void undeployCaller(VertxTestContext testContext) {
    caller.undeploy().onComplete(testContext.succeedingThenComplete());
  }

  // --- the caller itself ---------------------------------------------------------------------

  @Test
  void callerHasExpectedThreadingModel() {
    if (kind() == CallerKind.NON_VERTX_THREAD) {
      assertNull(caller.context());
    } else {
      assertNotNull(caller.context());
      assertEquals(kind().threadingModel(), caller.context().threadingModel());
    }
  }

  // --- well-behaved operations pass ----------------------------------------------------------

  @Test
  void futureCompletedLaterOnCallerContextPasses(VertxTestContext testContext) {
    expectValue(caller.assertCompletesOnCallerContext(ctx -> vertx.timer(20).map(v -> "ok")), "ok", testContext);
  }

  @Test
  void futureCompletedFromForeignThreadThroughOwnerContextPasses(VertxTestContext testContext) {
    expectValue(caller.assertCompletesOnCallerContext(ctx -> {
      Context owner = owner(ctx);
      Promise<String> promise = Promise.promise();
      plainThread(() -> owner.runOnContext(v -> promise.complete("ok")));
      return promise.future();
    }), "ok", testContext);
  }

  @Test
  void alreadyCompletedFuturePasses(VertxTestContext testContext) {
    expectValue(caller.assertCompletesOnCallerContext(ctx -> Future.succeededFuture("ok")), "ok", testContext);
  }

  @Test
  void operationFailureIsPropagatedUnchanged(VertxTestContext testContext) {
    caller.assertCompletesOnCallerContext(ctx -> Future.failedFuture(new IllegalStateException("boom")))
      .onComplete(testContext.failing(err -> testContext.verify(() -> {
        assertInstanceOf(IllegalStateException.class, err);
        assertEquals("boom", err.getMessage());
        testContext.completeNow();
      })));
  }

  @Test
  void wellBehavedReadStreamPasses(VertxTestContext testContext) {
    List<String> items = Arrays.asList("a", "b", "c", "d", "e");
    expectValue(caller.assertReadStreamOnCallerContext(ctx ->
      Future.succeededFuture(new FakeReadStream<>(owner(ctx), null, FakeReadStream.Fault.NONE, items))), items, testContext);
  }

  @Test
  void emptyReadStreamPasses(VertxTestContext testContext) {
    expectValue(caller.assertReadStreamOnCallerContext(ctx ->
        Future.succeededFuture(new FakeReadStream<String>(owner(ctx), null, FakeReadStream.Fault.NONE, Collections.emptyList()))),
      Collections.emptyList(), testContext);
  }

  @Test
  void wellBehavedWriteStreamPassesAndExercisesDrain(VertxTestContext testContext) {
    List<Integer> items = IntStream.range(0, 10).boxed().collect(Collectors.toList());
    AtomicReference<FakeWriteStream<Integer>> stream = new AtomicReference<>();
    caller.assertWriteStreamOnCallerContext(ctx -> {
      stream.set(new FakeWriteStream<>(owner(ctx), null, FakeWriteStream.Fault.NONE));
      return Future.succeededFuture(stream.get());
    }, items).onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      assertEquals(items, stream.get().written());
      assertTrue(stream.get().drains() > 0, "backpressure (drainHandler) was not exercised");
      testContext.completeNow();
    })));
  }

  // --- deliberately wrong operations fail ----------------------------------------------------

  @Test
  void futureCompletedFromPlainThreadFails(VertxTestContext testContext) {
    assumeTrue(kind() != CallerKind.NON_VERTX_THREAD, "a non-Vert.x caller has no context to return to");
    expectViolation(caller.assertCompletesOnCallerContext(ctx -> {
      Promise<String> promise = Promise.promise();
      afterCurrentCallerTask(ctx, () -> plainThread(() -> promise.complete("wrong")));
      return promise.future();
    }), "Future completion ran on no Vert.x context", testContext);
  }

  @Test
  void futureCompletedOnAnotherContextFails(VertxTestContext testContext) {
    assumeTrue(kind() != CallerKind.NON_VERTX_THREAD, "a non-Vert.x caller has no context to return to");
    Context other = vertx.getOrCreateContext(); // JUnit thread: a fresh context, never the caller's
    expectViolation(caller.assertCompletesOnCallerContext(ctx -> {
      Promise<String> promise = Promise.promise();
      afterCurrentCallerTask(ctx, () -> other.runOnContext(v -> promise.complete("wrong")));
      return promise.future();
    }), "expected the caller's", testContext);
  }

  @Test
  void hangingOperationFails(VertxTestContext testContext) {
    expectViolation(caller.withTimeout(Duration.ofMillis(200))
      .assertCompletesOnCallerContext(ctx -> Promise.<String>promise().future()), "did not complete within", testContext);
  }

  @Test
  void synchronousThrowFails(VertxTestContext testContext) {
    expectViolation(caller.assertCompletesOnCallerContext(ctx -> {
      throw new IllegalArgumentException("bad argument");
    }), "threw synchronously", testContext);
  }

  @Test
  void readStreamEmittingFromPlainThreadFails(VertxTestContext testContext) {
    expectViolation(caller.assertReadStreamOnCallerContext(ctx -> Future.succeededFuture(
      new FakeReadStream<>(owner(ctx), null, FakeReadStream.Fault.EMIT_FROM_PLAIN_THREAD, Arrays.asList("a", "b")))),
      "handler ran", testContext);
  }

  @Test
  void readStreamEndingOnAnotherContextFails(VertxTestContext testContext) {
    Context other = vertx.getOrCreateContext();
    expectViolation(caller.assertReadStreamOnCallerContext(ctx -> Future.succeededFuture(
      new FakeReadStream<>(owner(ctx), other, FakeReadStream.Fault.END_ON_OTHER_CONTEXT, Arrays.asList("a", "b", "c")))),
      "endHandler ran on", testContext);
  }

  @Test
  void readStreamIgnoringPauseFails(VertxTestContext testContext) {
    expectViolation(caller.assertReadStreamOnCallerContext(ctx -> Future.succeededFuture(
      new FakeReadStream<>(owner(ctx), null, FakeReadStream.Fault.IGNORE_PAUSE, Arrays.asList("a", "b", "c")))),
      "emitted while paused", testContext);
  }

  @Test
  void writeStreamDrainingOnAnotherContextFails(VertxTestContext testContext) {
    Context other = vertx.getOrCreateContext();
    List<Integer> items = IntStream.range(0, 10).boxed().collect(Collectors.toList());
    expectViolation(caller.assertWriteStreamOnCallerContext(ctx ->
      Future.succeededFuture(new FakeWriteStream<>(owner(ctx), other, FakeWriteStream.Fault.DRAIN_ON_OTHER_CONTEXT)), items),
      "drainHandler ran on", testContext);
  }

  @Test
  void writeFutureCompletedFromPlainThreadFails(VertxTestContext testContext) {
    List<Integer> items = IntStream.range(0, 4).boxed().collect(Collectors.toList());
    expectViolation(caller.assertWriteStreamOnCallerContext(ctx ->
      Future.succeededFuture(new FakeWriteStream<>(owner(ctx), null, FakeWriteStream.Fault.WRITE_FUTURE_FROM_PLAIN_THREAD)), items),
      "write Future ran", testContext);
  }

  // --- helpers -------------------------------------------------------------------------------

  /** The context the fakes belong to: the caller's, or a fresh one for a non-Vert.x caller. */
  private Context owner(Context callerCtx) {
    return callerCtx != null ? callerCtx : vertx.getOrCreateContext();
  }

  /**
   * Runs {@code action} only after the current task on the caller's context has finished, that
   * is, after the harness has attached its listener to the returned Future. Contexts run their
   * tasks one at a time, so this is deterministic. Without it, a wrong completion could win the
   * race and complete the Future before the listener is attached. The listener would then run
   * synchronously on the caller's thread, which is indistinguishable from correct behaviour.
   */
  private static void afterCurrentCallerTask(Context callerCtx, Runnable action) {
    callerCtx.runOnContext(v -> action.run());
  }

  private static void plainThread(Runnable task) {
    Thread thread = new Thread(task, "test-plain-thread");
    thread.setDaemon(true);
    thread.start();
  }

  private static <T> void expectValue(Future<T> future, T expected, VertxTestContext testContext) {
    future.onComplete(testContext.succeeding(value -> testContext.verify(() -> {
      assertEquals(expected, value);
      testContext.completeNow();
    })));
  }

  private static void expectViolation(Future<?> future, String messageFragment, VertxTestContext testContext) {
    future.onComplete(testContext.failing(err -> testContext.verify(() -> {
      assertInstanceOf(AssertionFailedError.class, err, () -> "expected a harness violation, got: " + err);
      assertTrue(err.getMessage().contains(messageFragment),
        () -> "violation message should contain '" + messageFragment + "' but was: " + err.getMessage());
      testContext.completeNow();
    })));
  }
}
