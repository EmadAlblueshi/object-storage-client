package io.github.emadalblueshi.vertx.s3;

import io.github.emadalblueshi.vertx.s3.context.CallerKind;

/** {@link S3Client} context tests with calls made from a worker verticle. */
class S3ClientWorkerContextTest extends S3ClientContextTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.WORKER;
  }
}
