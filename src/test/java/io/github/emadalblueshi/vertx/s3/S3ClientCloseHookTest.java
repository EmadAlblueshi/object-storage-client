package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Lifecycle via the close hook: a client created inside a verticle is closed when the verticle
 * is undeployed, like a Vert.x HttpClient; explicit close removes the hook. No server is
 * needed: a closed client fails before any I/O, and an open one fails to connect to port 1.
 */
@ExtendWith(VertxExtension.class)
class S3ClientCloseHookTest {

  private static final S3ClientOptions OPTIONS = new S3ClientOptions()
    .setEndpoint("http://localhost:1")
    .setRegion("test-west-1")
    .setCredentials(new S3Credentials("access", "secret"));

  @Test
  void clientCreatedInAVerticleIsClosedOnUndeploy(Vertx vertx, VertxTestContext testContext) {
    AtomicReference<S3Client> created = new AtomicReference<>();
    vertx.deployVerticle(new ClientVerticle(created))
      .compose(id -> expectOpen(created.get()).map(id))
      .compose(vertx::undeploy)
      .compose(v -> created.get().listBuckets())
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        // Exactly our message: Vert.x also closes the inner HttpClient on undeploy, and its own
        // "closed" failure must not be mistaken for the client's close hook having run.
        assertEquals("The S3 client is closed", e.getMessage());
        testContext.completeNow();
      })));
  }

  @Test
  void explicitCloseThenUndeploy(Vertx vertx, VertxTestContext testContext) {
    AtomicReference<S3Client> created = new AtomicReference<>();
    vertx.deployVerticle(new ClientVerticle(created))
      .compose(id -> created.get().close().map(id))
      .compose(vertx::undeploy)
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  void clientCreatedOutsideAVerticleSurvivesOtherUndeploys(Vertx vertx, VertxTestContext testContext) {
    S3Client client = S3Client.create(vertx, OPTIONS);
    vertx.deployVerticle(new ClientVerticle(new AtomicReference<>()))
      .compose(vertx::undeploy)
      .compose(v -> expectOpen(client))
      .eventually(client::close)
      .onComplete(testContext.succeedingThenComplete());
  }

  /** Succeeds if the client is open: the request reaches the network and fails to connect, not with "closed". */
  private static Future<Void> expectOpen(S3Client client) {
    return client.listBuckets().transform(ar -> {
      assertTrue(ar.failed(), "nothing listens on port 1");
      assertFalse(ar.cause().getMessage().contains("closed"), "client should be open: " + ar.cause().getMessage());
      return Future.succeededFuture();
    });
  }

  private static final class ClientVerticle extends VerticleBase {
    private final AtomicReference<S3Client> created;

    ClientVerticle(AtomicReference<S3Client> created) {
      this.created = created;
    }

    @Override
    public Future<?> start() {
      created.set(S3Client.create(vertx, OPTIONS));
      return Future.succeededFuture();
    }
  }
}
