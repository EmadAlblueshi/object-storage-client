package io.github.emadalblueshi.vertx.s3;

import io.vertx.core.VertxException;

/**
 * Base class of every failure reported by the S3 client, so callers can handle all of them with
 * one type:
 * <ul>
 *   <li>{@link S3ServiceException}: the server answered with an error response (HTTP status,
 *   S3 error code, request id).</li>
 *   <li>{@link S3ClientException}: anything else, such as connection or TLS failures, an
 *   unreadable response, an invalid argument, or a closed client.</li>
 * </ul>
 * It is a {@link VertxException}, like the exceptions of Vert.x's own clients.
 */
public abstract class S3Exception extends VertxException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message the message
   * @param cause the cause, or {@code null}
   */
  protected S3Exception(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * @param message the message
   * @param cause the cause, or {@code null}
   * @param noStackTrace {@code true} to skip capturing a stack trace (and suppressed exceptions)
   */
  protected S3Exception(String message, Throwable cause, boolean noStackTrace) {
    super(message, cause, noStackTrace);
  }
}
