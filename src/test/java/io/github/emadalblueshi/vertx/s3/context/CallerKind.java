package io.github.emadalblueshi.vertx.s3.context;

import io.vertx.core.ThreadingModel;

/**
 * Where a call into the library comes from.
 */
public enum CallerKind {

  /** A standard verticle running on an event loop. */
  EVENT_LOOP(ThreadingModel.EVENT_LOOP),

  /** A worker verticle. */
  WORKER(ThreadingModel.WORKER),

  /** A virtual-thread verticle (JDK 21+; tests are tagged {@code virtual-threads}). */
  VIRTUAL_THREAD(ThreadingModel.VIRTUAL_THREAD),

  /**
   * A plain Java thread with no Vert.x context. There is no caller context to return to, so the
   * only requirement is that the call completes (without hanging), and that stream handlers stay
   * on one Vert.x context.
   */
  NON_VERTX_THREAD(null);

  private final ThreadingModel threadingModel;

  CallerKind(ThreadingModel threadingModel) {
    this.threadingModel = threadingModel;
  }

  /** The threading model of the caller verticle, or {@code null} for {@link #NON_VERTX_THREAD}. */
  public ThreadingModel threadingModel() {
    return threadingModel;
  }
}
