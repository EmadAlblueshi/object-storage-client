package io.github.emadalblueshi.vertx.s3;

import io.github.emadalblueshi.vertx.s3.context.CallerKind;

/** {@link S3Client} context tests with calls made from a plain, non-Vert.x thread. */
class S3ClientNonVertxThreadContextTest extends S3ClientContextTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.NON_VERTX_THREAD;
  }
}
