package io.github.emadalblueshi.vertx.s3.context;

/** {@link CallerContext} self-tests with calls made from a plain, non-Vert.x thread. */
class NonVertxThreadContextHarnessTest extends ContextHarnessSelfTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.NON_VERTX_THREAD;
  }
}
