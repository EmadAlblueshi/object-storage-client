package io.github.emadalblueshi.vertx.s3;

/**
 * A failure that is not an error response from the server: connection or TLS failures, an
 * unreadable response, a failing credentials provider, or a closed client. The underlying
 * exception, if any, is the {@linkplain #getCause() cause}. It keeps its stack trace: for a
 * validation failure, the trace points at the caller.
 */
public class S3ClientException extends S3Exception {

  private static final long serialVersionUID = 1L;

  public S3ClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
