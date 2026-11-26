package io.github.emadalblueshi.vertx.s3.context;

import org.junit.jupiter.api.Tag;

/** {@link CallerContext} self-tests with calls made from a virtual-thread verticle (JDK 21+). */
@Tag("virtual-threads")
class VirtualThreadContextHarnessTest extends ContextHarnessSelfTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.VIRTUAL_THREAD;
  }
}
