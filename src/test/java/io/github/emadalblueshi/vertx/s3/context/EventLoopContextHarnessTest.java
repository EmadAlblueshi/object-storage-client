package io.github.emadalblueshi.vertx.s3.context;

/** {@link CallerContext} self-tests with calls made from a event-loop verticle. */
class EventLoopContextHarnessTest extends ContextHarnessSelfTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.EVENT_LOOP;
  }
}
