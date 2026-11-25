package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The log-redaction suite against Silo over HTTPS (UNSIGNED-PAYLOAD). */
class S3ClientLogRedactionSiloTlsTest extends S3ClientLogRedactionTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSiloTls();
  }
}
