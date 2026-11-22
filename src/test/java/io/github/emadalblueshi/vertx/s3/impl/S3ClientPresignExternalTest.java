package io.github.emadalblueshi.vertx.s3.impl;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.ExternalBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.junit5.Timeout;

/**
 * The presign suite against the opt-in external backend (see {@link ExternalBackend}); skipped
 * unless it is configured. Creates exactly one bucket. A typical endpoint has the default port, so
 * the URLs and the signed {@code Host} carry none. With {@code S3_PATH_STYLE=false} the URLs are
 * virtual-hosted-style ({@code bucket.host}).
 */
@EnabledIf(value = "configured", disabledReason = ExternalBackend.DISABLED_REASON)
@Timeout(value = 2, timeUnit = TimeUnit.MINUTES)
class S3ClientPresignExternalTest extends S3ClientPresignTestBase {

  /** Skips the class unless the external backend is configured (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return ExternalBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return ExternalBackend.fromEnvironment();
  }
}
