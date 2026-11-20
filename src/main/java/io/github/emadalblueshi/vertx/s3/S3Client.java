package io.github.emadalblueshi.vertx.s3;

import io.vertx.core.Future;

/**
 * Asynchronous client for Amazon S3 and S3-compatible storage.
 * <p>
 * Every asynchronous method captures the caller's Vert.x context at call time and completes
 * its {@link Future} on that context (event-loop, worker or virtual-thread). Operations are
 * added in Phase 2 of the revamp.
 */
public interface S3Client {

  /**
   * Closes the client and releases its resources.
   *
   * @return a future completed on the caller's context once the client is closed
   */
  Future<Void> close();
}
