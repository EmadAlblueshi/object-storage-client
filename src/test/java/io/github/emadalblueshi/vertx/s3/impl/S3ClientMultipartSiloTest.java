package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;

/** The multipart suite against Silo over HTTP. */
class S3ClientMultipartSiloTest extends S3ClientMultipartTestBase {

  @Override
  protected S3TestBackend backend() {
    return S3TestBackend.sharedSilo();
  }

  /** Silo, like MinIO, only matches a ListMultipartUploads prefix against whole keys. */
  @Override
  protected boolean prefixMatchesKeyStarts() {
    return false;
  }

  /** Silo, like MinIO, answers 204 when aborting an upload that no longer exists. */
  @Override
  protected boolean abortOfFinishedUploadFails() {
    return false;
  }

  /** Silo ignores max-uploads and the markers: every open upload comes back in one page. */
  @Override
  protected boolean pagesUploadListings() {
    return false;
  }
}
