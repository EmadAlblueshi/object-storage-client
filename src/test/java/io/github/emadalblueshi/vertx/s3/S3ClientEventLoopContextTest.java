package io.github.emadalblueshi.vertx.s3;

import io.github.emadalblueshi.vertx.s3.context.CallerKind;

/** {@link S3Client} context tests with calls made from an event-loop verticle. */
class S3ClientEventLoopContextTest extends S3ClientContextTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.EVENT_LOOP;
  }
}
