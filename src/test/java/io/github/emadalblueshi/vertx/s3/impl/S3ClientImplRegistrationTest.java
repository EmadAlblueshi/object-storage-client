package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.vertx.core.Closeable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.internal.CloseFuture;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.VertxInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * How a client is registered with its owner (the creating verticle's context, or the Vertx
 * instance), observed on the owner's close future: {@code CloseFuture.remove} reports whether the
 * registration was still there. An explicitly closed client must not stay referenced by its owner
 * until undeploy.
 */
@ExtendWith(VertxExtension.class)
class S3ClientImplRegistrationTest {

  private static final S3ClientOptions OPTIONS = new S3ClientOptions()
    .setEndpoint("http://localhost:1")
    .setRegion("test-west-1")
    .setCredentials(new S3Credentials("access", "secret"));

  @Test
  void explicitCloseUnregistersFromTheVerticleContext(Vertx vertx, VertxTestContext testContext) {
    AtomicReference<Context> verticleContext = new AtomicReference<>();
    AtomicReference<S3ClientImpl> open = new AtomicReference<>();
    AtomicReference<S3ClientImpl> closed = new AtomicReference<>();
    vertx.deployVerticle(new VerticleBase() {
      @Override
      public Future<?> start() {
        verticleContext.set(context);
        open.set((S3ClientImpl) S3Client.create(vertx, OPTIONS));
        closed.set((S3ClientImpl) S3Client.create(vertx, OPTIONS));
        return closed.get().close();
      }
    }).compose(id -> {
      CloseFuture owner = ((ContextInternal) verticleContext.get()).closeFuture();
      testContext.verify(() -> {
        assertTrue(closed.get().isClosed());
        assertFalse(owner.remove(registered(closed.get())), "an explicitly closed client is no longer registered");
        assertTrue(owner.remove(registered(open.get())), "an open client is registered with the verticle's context");
      });
      return open.get().close().map(id);
    }).compose(vertx::undeploy).onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void clientCreatedOutsideAVerticleIsRegisteredWithVertx(Vertx vertx, VertxTestContext testContext) {
    CloseFuture owner = ((VertxInternal) vertx).closeFuture();
    S3ClientImpl open = (S3ClientImpl) S3Client.create(vertx, OPTIONS);
    S3ClientImpl closed = (S3ClientImpl) S3Client.create(vertx, OPTIONS);
    closed.close().onComplete(testContext.succeeding(v -> testContext.verify(() -> {
      assertFalse(owner.remove(registered(closed)), "an explicitly closed client is no longer registered");
      assertTrue(owner.remove(registered(open)), "an open client is registered with the Vertx instance");
      open.close().onComplete(testContext.succeedingThenComplete());
    })));
  }

  @Test
  void creatingAClientOnAnUndeployedVerticleContextFails(Vertx vertx, VertxTestContext testContext) {
    AtomicReference<Context> verticleContext = new AtomicReference<>();
    vertx.deployVerticle(new VerticleBase() {
      @Override
      public Future<?> start() {
        verticleContext.set(context);
        return Future.succeededFuture();
      }
    }).compose(vertx::undeploy).onComplete(testContext.succeeding(v -> verticleContext.get().runOnContext(x -> {
      // The context outlives its deployment, but its close future is closed: nothing could close the client
      testContext.verify(() -> {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> S3Client.create(vertx, OPTIONS));
        assertEquals("The Vert.x instance or the creating context is already closed", e.getMessage());
      });
      testContext.completeNow();
    })));
  }

  private static Closeable registered(S3ClientImpl client) {
    return (Closeable) client.registration().registered();
  }
}
