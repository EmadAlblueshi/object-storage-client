package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.ExternalBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.junit5.Timeout;

/**
 * The object suite against the opt-in external backend (see {@link ExternalBackend}); skipped
 * unless it is configured. Over an {@code https://} endpoint every request, bodiless or
 * buffered, uses UNSIGNED-PAYLOAD, so the whole suite verifies that the server accepts it.
 * Keys with empty, {@code .} or {@code ..} segments, and a leading {@code /}, are expected to
 * behave as on AWS. With {@code S3_PATH_STYLE=false} it runs with virtual-hosted-style addressing.
 */
@EnabledIf(value = "configured", disabledReason = ExternalBackend.DISABLED_REASON)
@Timeout(value = 3, timeUnit = TimeUnit.MINUTES)
class S3ClientObjectExternalTest extends S3ClientObjectTestBase {

  /** Skips the class unless the external backend is configured (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return ExternalBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return ExternalBackend.fromEnvironment();
  }

  @Override
  protected boolean rejectsDotSegmentKeys() {
    return false;
  }

  @Override
  protected boolean stripsLeadingSlash() {
    return false;
  }

  /** Less data over a real network. */
  @Override
  protected int largeObjectSize() {
    return 8 * 1024 * 1024;
  }

  /** States what the suite proves about payload signing; skipped for an {@code http://} endpoint (signed payloads). */
  @Test
  void requestsUseUnsignedPayload() {
    Assumptions.assumeTrue(backend.tls(), "UNSIGNED-PAYLOAD is only used over https://; S3_ENDPOINT is http://");
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(backend.tls(), -1), "bodiless requests");
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(backend.tls(), 3 * 1024 * 1024), "buffered requests");
  }
}
