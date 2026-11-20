package io.github.emadalblueshi.vertx.s3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server answered with an error response (HTTP status 3xx, 4xx or 5xx).
 * <p>
 * {@link #getErrorCode()} is the S3 error code from the response's {@code <Error>} document,
 * e.g. {@code NoSuchBucket}, {@code AccessDenied}, {@code SignatureDoesNotMatch}. It is
 * {@code null} when the response has no error document, as for HEAD requests, {@code 304}, or a
 * proxy's HTML error page; use {@link #getStatusCode()} then.
 */
public class S3ServiceException extends S3Exception {

  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final String errorCode;
  private final String requestId;
  private final String hostId;
  private final String resource;
  private final Map<String, String> details;

  /**
   * @param message human-readable message
   * @param statusCode HTTP status code
   * @param errorCode S3 error code, or {@code null}
   * @param requestId request id ({@code x-amz-request-id}), or {@code null}
   * @param hostId extended request id ({@code x-amz-id-2}), or {@code null}
   * @param resource the resource the error refers to, or {@code null}
   * @param details further fields of the error document, e.g. {@code Region}, {@code BucketName},
   *     {@code Endpoint} (copied; may be empty)
   */
  public S3ServiceException(String message, int statusCode, String errorCode, String requestId, String hostId,
                            String resource, Map<String, String> details) {
    super(message, null);
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.requestId = requestId;
    this.hostId = hostId;
    this.resource = resource;
    this.details = details == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  /** The HTTP status code. */
  public int getStatusCode() {
    return statusCode;
  }

  /** The S3 error code, or {@code null} if the response had no error document. */
  public String getErrorCode() {
    return errorCode;
  }

  /** The request id ({@code x-amz-request-id}), or {@code null}. Quote it in support requests. */
  public String getRequestId() {
    return requestId;
  }

  /** The extended request id ({@code HostId} / {@code x-amz-id-2}), or {@code null}. */
  public String getHostId() {
    return hostId;
  }

  /** The resource the error refers to, or {@code null}. */
  public String getResource() {
    return resource;
  }

  /**
   * Further fields of the error document, e.g. {@code Region}, {@code BucketName},
   * {@code Endpoint}, plus {@code BucketRegion} from the {@code x-amz-bucket-region} header. Never
   * {@code null}.
   */
  public Map<String, String> getDetails() {
    return details;
  }
}
