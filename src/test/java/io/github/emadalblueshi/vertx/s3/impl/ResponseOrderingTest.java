package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * How a buffered body ({@link Transport#readBody}) and an object stream's cancel react to every
 * ordering of response events around a decision, with each ordering scripted explicitly on a fake
 * response. Everything runs synchronously in one task on one context, so the order of events is
 * exactly the order written in the test, on any machine.
 */
@ExtendWith(VertxExtension.class)
class ResponseOrderingTest {

  private static final int LIMIT = 10_000;
  private static final LongFunction<S3ClientException> TOO_LARGE =
    size -> new S3ClientException("too large" + (size >= 0 ? " (" + size + ")" : ""), null);

  @Test
  void limitErrorWinsWhenTheResponseHadAlreadyEndedAtTheAbort(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      // The whole response has already arrived: cancelling delivers its end and a failure, and
      // the cancellation itself fails, as for a response that has already ended
      FakeResponse response = new FakeResponse(-1, r -> {
        r.end();
        r.fail(new IllegalStateException("Response already ended"));
        return Future.failedFuture(new IllegalStateException("Response already ended"));
      });
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, LIMIT, TOO_LARGE);
      response.chunk(6_000);
      response.chunk(6_000);
      return result.transform(ar -> {
        assertTrue(ar.failed());
        assertEquals("too large", ar.cause().getMessage());
        assertEquals(1, response.cancels);
        return Future.succeededFuture();
      });
    });
  }

  @Test
  void limitErrorWinsWhenCancellingThrows(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(-1, r -> {
        throw new IllegalStateException("Response already ended");
      });
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, LIMIT, TOO_LARGE);
      response.chunk(LIMIT + 1);
      return result.transform(ar -> {
        assertEquals("too large", ar.cause().getMessage());
        return Future.succeededFuture();
      });
    });
  }

  @Test
  void eventsAfterTheLimitAreIgnoredWhenTheEndComesAfterTheAbort(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      // The end is withheld until after the abort; late events still reach the handlers that
      // were set before the abort (events already in flight)
      FakeResponse response = new FakeResponse(-1, r -> Future.succeededFuture(true));
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, LIMIT, TOO_LARGE);
      Handler<Buffer> dataHandler = response.handler;
      Handler<Void> endHandler = response.endHandler;
      Handler<Throwable> exceptionHandler = response.exceptionHandler;
      response.chunk(LIMIT + 1);
      assertNull(response.handler, "the data handler is removed once the limit is crossed");
      dataHandler.handle(Buffer.buffer(new byte[10]));
      endHandler.handle(null);
      exceptionHandler.handle(new IllegalStateException("closed"));
      return result.transform(ar -> {
        assertEquals("too large", ar.cause().getMessage());
        assertEquals(1, response.cancels);
        return Future.succeededFuture();
      });
    });
  }

  @Test
  void announcedLengthOverTheLimitFailsEvenIfCancellingFails(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(20_000, r -> Future.failedFuture("Response already ended"));
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, LIMIT, TOO_LARGE);
      return result.transform(ar -> {
        assertEquals("too large (20000)", ar.cause().getMessage());
        assertEquals(1, response.cancels);
        assertNull(response.handler, "no body is read");
        return Future.succeededFuture();
      });
    });
  }

  @Test
  void truncatedBodyIsFinalWhateverFollows(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(-1, r -> {
        r.end();
        return Future.failedFuture("Response already ended");
      });
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, 10, null);
      Handler<Buffer> dataHandler = response.handler;
      response.chunk("12345678");
      response.chunk("90abc");
      dataHandler.handle(Buffer.buffer("zz"));
      return result.map(body -> {
        assertEquals("1234567890", body.toString());
        assertEquals(1, response.cancels);
        return null;
      });
    });
  }

  @Test
  void failureBeforeAnyDecisionFails(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(-1, r -> Future.succeededFuture(true));
      Future<Buffer> result = Transport.readBody(ContextSupport.capture(vertx), response.proxy, LIMIT, TOO_LARGE);
      response.chunk(100);
      response.fail(new IllegalStateException("connection reset"));
      response.end();
      return result.transform(ar -> {
        assertEquals("connection reset", ar.cause().getMessage());
        assertEquals(0, response.cancels);
        return Future.succeededFuture();
      });
    });
  }

  @Test
  void streamCancelSucceedsWhenTheResponseHadAlreadyEnded(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(-1, r -> Future.failedFuture("Stream already reset"));
      ContextSupport.Caller caller = ContextSupport.capture(vertx);
      ObjectStreamImpl stream = new ObjectStreamImpl(vertx, caller, response.proxy, new S3ObjectMetadata());
      List<Throwable> errors = new ArrayList<>();
      List<Buffer> chunks = new ArrayList<>();
      stream.exceptionHandler(errors::add).handler(chunks::add).endHandler(v -> errors.add(new AssertionError("end")));
      return stream.cancel().compose(v -> {
        response.chunk(10); // already received: drained after the cancel, never delivered
        response.end();
        // the cancellation failure is delivered in a later task on the stream's context
        return runLater(vertx);
      }).map(v -> {
        assertEquals(1, response.cancels);
        assertTrue(response.resumed, "resumed, so anything already received is drained");
        assertTrue(chunks.isEmpty(), chunks.toString());
        assertEquals(1, errors.size(), errors.toString());
        assertEquals("The object stream was cancelled", assertInstanceOf(S3ClientException.class, errors.get(0)).getMessage());
        return null;
      });
    });
  }

  @Test
  void streamCancelSucceedsWhenCancellingThrows(Vertx vertx, VertxTestContext testContext) {
    onContext(vertx, testContext, () -> {
      FakeResponse response = new FakeResponse(-1, r -> {
        throw new IllegalStateException("closed");
      });
      ObjectStreamImpl stream = new ObjectStreamImpl(vertx, ContextSupport.capture(vertx), response.proxy,
        new S3ObjectMetadata());
      return stream.cancel().map(v -> {
        assertTrue(response.resumed);
        assertFalse(stream.cancel().failed(), "a second cancel is a no-op");
        return null;
      });
    });
  }

  // --- helpers ---------------------------------------------------------------------------------------

  /** Runs {@code scenario} in one task on a fresh event-loop context; the test passes if its Future succeeds. */
  private static void onContext(Vertx vertx, VertxTestContext testContext, java.util.function.Supplier<Future<?>> scenario) {
    vertx.getOrCreateContext().runOnContext(v -> {
      Future<?> outcome;
      try {
        outcome = scenario.get();
      } catch (Throwable t) {
        testContext.failNow(t);
        return;
      }
      outcome.onComplete(testContext.succeedingThenComplete());
    });
  }

  /** Completes after every task already queued on the current context has run. */
  private static Future<Void> runLater(Vertx vertx) {
    io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
    vertx.getOrCreateContext().runOnContext(v -> promise.complete());
    return promise.future();
  }

  /**
   * A response whose events are delivered only when the test calls {@link #chunk}, {@link #end}
   * or {@link #fail}, to the handlers currently set. Cancelling its request runs {@code onCancel},
   * which may deliver events itself and returns the cancellation's outcome (or throws).
   */
  private static final class FakeResponse {

    final HttpClientResponse proxy;
    Handler<Buffer> handler;
    Handler<Void> endHandler;
    Handler<Throwable> exceptionHandler;
    int cancels;
    boolean resumed;

    FakeResponse(long contentLength, Function<FakeResponse, Future<Boolean>> onCancel) {
      HttpClientRequest request = (HttpClientRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {HttpClientRequest.class}, (p, method, args) -> {
          switch (method.getName()) {
            case "getMethod":
              return HttpMethod.GET;
            case "cancel":
              cancels++;
              return onCancel.apply(this);
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
      this.proxy = (HttpClientResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {HttpClientResponse.class}, (p, method, args) -> {
          switch (method.getName()) {
            case "request":
              return request;
            case "getHeader":
              return contentLength >= 0 && "content-length".equalsIgnoreCase(args[0].toString())
                ? Long.toString(contentLength) : null;
            case "handler":
              handler = cast(args[0]);
              return p;
            case "endHandler":
              endHandler = cast(args[0]);
              return p;
            case "exceptionHandler":
              exceptionHandler = cast(args[0]);
              return p;
            case "pause":
            case "fetch":
              return p;
            case "resume":
              resumed = true;
              return p;
            default:
              throw new UnsupportedOperationException(method.getName());
          }
        });
    }

    void chunk(int size) {
      chunk(Buffer.buffer(new byte[size]));
    }

    void chunk(String text) {
      chunk(Buffer.buffer(text));
    }

    void chunk(Buffer data) {
      if (handler != null) {
        handler.handle(data);
      }
    }

    void end() {
      if (endHandler != null) {
        endHandler.handle(null);
      }
    }

    void fail(Throwable error) {
      if (exceptionHandler != null) {
        exceptionHandler.handle(error);
      }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
      return (T) value;
    }
  }
}
