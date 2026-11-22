package io.github.emadalblueshi.vertx.s3.impl;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.ExternalBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.vertx.junit5.Timeout;

/**
 * The multipart suite against the opt-in external backend (see {@link ExternalBackend}); skipped
 * unless it is configured. Creates exactly one bucket and uploads about 35 MB in all. It expects
 * AWS behaviour: a ListMultipartUploads prefix matches the start of keys, and aborting an upload
 * that no longer exists fails with 404 NoSuchUpload.
 */
@EnabledIf(value = "configured", disabledReason = ExternalBackend.DISABLED_REASON)
@Timeout(value = 3, timeUnit = TimeUnit.MINUTES)
class S3ClientMultipartExternalTest extends S3ClientMultipartTestBase {

  /** Skips the class unless the external backend is configured (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return ExternalBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return ExternalBackend.fromEnvironment();
  }
}
