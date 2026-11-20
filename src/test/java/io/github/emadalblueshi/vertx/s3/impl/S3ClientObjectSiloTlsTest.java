package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The object suite against Silo over HTTPS: every request uses UNSIGNED-PAYLOAD. */
class S3ClientObjectSiloTlsTest extends S3ClientObjectTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSiloTls();
  }

  @Override
  protected boolean rejectsDotSegmentKeys() {
    return true;
  }

  @Override
  protected boolean stripsLeadingSlash() {
    return true;
  }
}
