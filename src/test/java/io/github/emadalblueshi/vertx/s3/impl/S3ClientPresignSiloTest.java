package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The presign suite against Silo over HTTP, on a non-default port (signed as {@code host:port}). */
class S3ClientPresignSiloTest extends S3ClientPresignTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSilo();
  }
}
