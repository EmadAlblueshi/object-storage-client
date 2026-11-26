package io.github.emadalblueshi.vertx.s3.impl;

import org.junit.jupiter.api.condition.EnabledIf;

import io.github.emadalblueshi.vertx.s3.backend.CephBackend;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The multipart suite against Ceph RGW over HTTP (opt-in: {@code S3_TEST_CEPH=true}), with AWS expectations. */
@EnabledIf(value = "configured", disabledReason = CephBackend.DISABLED_REASON)
class S3ClientMultipartCephTest extends S3ClientMultipartTestBase {

  /** Skips the class unless the Ceph backend is enabled (a local method: JUnit can only reflect on this package). */
  static boolean configured() {
    return CephBackend.configured();
  }

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedCeph();
  }

  /** Ceph RGW answers ListParts for an aborted upload with 404 NoSuchKey (AWS: NoSuchUpload). */
  @Override
  protected String listPartsAfterAbortCode() {
    return "NoSuchKey";
  }
}
