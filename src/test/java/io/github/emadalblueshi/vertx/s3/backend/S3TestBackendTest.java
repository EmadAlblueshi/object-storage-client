package io.github.emadalblueshi.vertx.s3.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Smoke tests for the test backends themselves: each backend starts, its endpoint matches its
 * TLS mode, and it is reachable with the options it provides. For TLS, an untrusted client is
 * rejected, which proves the self-signed certificate is really in use.
 */
@ExtendWith(VertxExtension.class)
class S3TestBackendTest {

  private static final S3TestBackend SILO = S3TestBackend.silo();
  private static final S3TestBackend SILO_TLS = S3TestBackend.siloTls();
  private static final List<S3TestBackend> BACKENDS = List.of(SILO, SILO_TLS);

  @BeforeAll
  static void startBackends() {
    BACKENDS.forEach(S3TestBackend::start);
  }

  @AfterAll
  static void stopBackends() {
    BACKENDS.forEach(S3TestBackend::stop);
  }

  static Stream<S3TestBackend> backends() {
    return BACKENDS.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void endpointMatchesTlsMode(S3TestBackend backend) {
    assertEquals(backend.tls() ? "https" : "http", backend.endpoint().getScheme());
    assertEquals(SiloContainer.REGION, backend.region());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("backends")
  void healthEndpointIsReachable(S3TestBackend backend, Vertx vertx, VertxTestContext testContext) {
    HttpClientAgent client = vertx.createHttpClient(backend.httpClientOptions());
    client.request(HttpMethod.GET, "/minio/health/live")
      .compose(HttpClientRequest::send)
      .eventually(() -> client.close())
      .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
        assertEquals(200, response.statusCode());
        testContext.completeNow();
      })));
  }

  @Test
  void untrustedTlsClientIsRejected(Vertx vertx, VertxTestContext testContext) {
    HttpClientOptions untrusted = SILO_TLS.httpClientOptions().setTrustOptions(null);
    HttpClientAgent client = vertx.createHttpClient(untrusted);
    client.request(HttpMethod.GET, "/minio/health/live")
      .compose(HttpClientRequest::send)
      .eventually(() -> client.close())
      .onComplete(testContext.failing(err -> testContext.verify(() -> {
        assertTrue(hasCause(err, SSLException.class), () -> "expected a TLS failure, got: " + err);
        testContext.completeNow();
      })));
  }

  private static boolean hasCause(Throwable err, Class<? extends Throwable> type) {
    for (Throwable t = err; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }
}
