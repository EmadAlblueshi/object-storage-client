package io.github.emadalblueshi.vertx.s3.impl;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.ExternalBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.junit5.Timeout;

/**
 * The log-redaction suite against the opt-in external backend (see {@link ExternalBackend});
 * skipped unless it is configured. A real account's error documents are what this checks, e.g.
 * AWS's signature-debugging fields. Failure messages never show a secret they found.
 */
@EnabledIf(value = "configured", disabledReason = ExternalBackend.DISABLED_REASON)
@Timeout(value = 3, timeUnit = TimeUnit.MINUTES)
class S3ClientLogRedactionExternalTest extends S3ClientLogRedactionTestBase {

  /** Skips the class unless the external backend is configured (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return ExternalBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return ExternalBackend.fromEnvironment();
  }
}
