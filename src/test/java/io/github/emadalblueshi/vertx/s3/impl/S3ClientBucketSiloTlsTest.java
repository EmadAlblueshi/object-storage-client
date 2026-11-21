package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The bucket suite against Silo over HTTPS (UNSIGNED-PAYLOAD). */
class S3ClientBucketSiloTlsTest extends S3ClientBucketTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSiloTls();
  }
}
