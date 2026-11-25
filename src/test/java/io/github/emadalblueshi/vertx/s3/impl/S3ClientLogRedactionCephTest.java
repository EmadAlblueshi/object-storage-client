package io.github.emadalblueshi.vertx.s3.impl;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.CephBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The log-redaction suite against Ceph RGW over HTTP (opt-in: {@code S3_TEST_CEPH=true}). */
@EnabledIf(value = "configured", disabledReason = CephBackend.DISABLED_REASON)
class S3ClientLogRedactionCephTest extends S3ClientLogRedactionTestBase {

  /** Skips the class unless the Ceph backend is enabled (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return CephBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedCeph();
  }
}
