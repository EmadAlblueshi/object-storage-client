package io.github.emadalblueshi.vertx.s3.context;

/** {@link CallerContext} self-tests with calls made from a worker verticle. */
class WorkerContextHarnessTest extends ContextHarnessSelfTestBase {

  @Override
  protected CallerKind kind() {
    return CallerKind.WORKER;
  }
}
