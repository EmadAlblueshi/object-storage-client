package io.github.emadalblueshi.vertx.s3.impl;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.CephBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The object suite against Ceph RGW over HTTPS (opt-in: {@code S3_TEST_CEPH=true}), with AWS expectations. */
@EnabledIf(value = "configured", disabledReason = CephBackend.DISABLED_REASON)
class S3ClientObjectCephTlsTest extends S3ClientObjectTestBase {

  /** Skips the class unless the Ceph backend is enabled (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return CephBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedCephTls();
  }

  /** Like AWS: keys with empty, {@code .} or {@code ..} segments are stored as they are. */
  @Override
  protected boolean rejectsDotSegmentKeys() {
    return false;
  }

  /** Like AWS: {@code /x} and {@code x} are distinct keys. */
  @Override
  protected boolean stripsLeadingSlash() {
    return false;
  }
}
