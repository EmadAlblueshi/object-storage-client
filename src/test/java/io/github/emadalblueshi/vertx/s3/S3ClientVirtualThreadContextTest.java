package io.github.emadalblueshi.vertx.s3;

import org.junit.jupiter.api.Tag;

import io.github.emadalblueshi.vertx.s3.context.CallerKind;

/** {@link S3Client} context tests with calls made from a virtual-thread verticle (JDK 21+). */
@Tag("virtual-threads")
class S3ClientVirtualThreadContextTest extends S3ClientContextTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.VIRTUAL_THREAD;
  }
}
