package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The object suite against Silo over HTTP: payloads are signed (inline, and via executeBlocking above 256 KiB). */
class S3ClientObjectSiloTest extends S3ClientObjectTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSilo();
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
