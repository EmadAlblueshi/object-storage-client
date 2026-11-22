package io.github.emadalblueshi.vertx.s3.impl;

import io.vertx.core.Future;

/** An upload in progress that the client aborts when it closes (streamed or file uploads). */
interface ActiveUpload {

  /** Starts the upload; the Future gives the object's ETag. */
  Future<String> start();

  /**
   * Fails the upload because the client is closing; the Future completes once it is aborted (or
   * finished anyway).
   */
  Future<Void> abortOnClose();
}
