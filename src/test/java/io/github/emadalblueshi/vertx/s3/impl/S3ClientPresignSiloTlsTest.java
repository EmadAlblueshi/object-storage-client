package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The presign suite against Silo over HTTPS, on a non-default port: {@code https://} URLs. */
class S3ClientPresignSiloTlsTest extends S3ClientPresignTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSiloTls();
  }

  /**
   * Silo, like MinIO, reports a missing signed header as if an unsigned header were present:
   * 400 AccessDenied ("There were headers present in the request which were not signed").
   */
  @Override
  protected int missingSignedHeaderStatus() {
    return 400;
  }

  @Override
  protected String missingSignedHeaderCode() {
    return "AccessDenied";
  }
}
