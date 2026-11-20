package io.github.emadalblueshi.vertx.s3;

/**
 * Base class of every failure reported by the S3 client, so callers can handle all of them with
 * one type:
 * <ul>
 *   <li>{@link S3ServiceException}: the server answered with an error response (HTTP status,
 *   S3 error code, request id).</li>
 *   <li>{@link S3ClientException}: anything else, such as connection or TLS failures, an
 *   unreadable response, failing credentials, or a closed client.</li>
 * </ul>
 */
public abstract class S3Exception extends RuntimeException {

  private static final long serialVersionUID = 1L;

  protected S3Exception(String message, Throwable cause) {
    super(message, cause);
  }
}
