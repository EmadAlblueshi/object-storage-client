package io.github.emadalblueshi.objectstorage.client.s3;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class S3ResponseException extends RuntimeException {

  private final String code;

  private final String resource;

  private final String requestId;

  public S3ResponseException(
      String code,
      String message,
      String resource,
      String requestId) {
    super(message);
    this.code = code;
    this.resource = resource;
    this.requestId = requestId;
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return super.getMessage();
  }

  public String getResource() {
    return resource;
  }

  public String getRequestId() {
    return requestId;
  }
}
