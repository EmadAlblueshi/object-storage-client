package io.github.emadalblueshi.vertx.s3;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * The body of an object being downloaded by {@link S3Client#getObjectStream}, with real
 * backpressure, plus its metadata.
 * <p>
 * The stream is handed out <strong>paused</strong>: no data flows until you set a handler and
 * call {@link #resume()} or {@link #fetch(long)}, or consume it with {@code pipeTo} (e.g. to an
 * {@code AsyncFile} or an {@code HttpServerResponse}) or {@code collect}, which resume it. Its handlers run on the context that
 * called {@code getObjectStream}. A failure while reading is reported to the
 * {@linkplain #exceptionHandler exception handler} as an {@link S3ClientException}.
 * <p>
 * <strong>Consume or cancel it.</strong> Until the stream ends, it holds a pooled HTTP
 * connection. A stream you don't read to the end must be {@linkplain #cancel() cancelled};
 * otherwise the connection is never released.
 */
public interface S3ObjectStream extends ReadStream<Buffer> {

  /** The object's metadata, from the response headers. */
  S3ObjectMetadata metadata();

  /**
   * Stops the download and releases its connection (it is closed, not reused). Idempotent, and a
   * no-op once the stream has ended. If the stream hadn't ended or failed, its exception handler
   * then receives an {@link S3ClientException} saying it was cancelled, so a pipe fails instead of
   * hanging; no data or end event follows.
   *
   * @return a future completed on the caller's context once the connection is released
   */
  Future<Void> cancel();

  @Override
  S3ObjectStream exceptionHandler(Handler<Throwable> handler);

  @Override
  S3ObjectStream handler(Handler<Buffer> handler);

  @Override
  S3ObjectStream pause();

  @Override
  S3ObjectStream resume();

  @Override
  S3ObjectStream fetch(long amount);

  @Override
  S3ObjectStream endHandler(Handler<Void> endHandler);
}
