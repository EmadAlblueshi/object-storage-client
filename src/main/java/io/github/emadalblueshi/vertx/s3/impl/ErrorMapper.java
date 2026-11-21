package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.emadalblueshi.vertx.s3.S3ServiceException;

/**
 * Maps an error response (status 3xx, 4xx or 5xx) to an {@link S3ServiceException}.
 * <ul>
 *   <li>With an S3 {@code <Error>} document: its code, message, resource, request ids and
 *   further fields.</li>
 *   <li>Without one (HEAD requests, {@code 304}, empty bodies): the HTTP status; the request ids
 *   come from the {@code x-amz-request-id} / {@code x-amz-id-2} headers.</li>
 *   <li>With a non-XML body (a proxy's HTML page, JSON): the status plus a short, sanitised
 *   excerpt of the body.</li>
 * </ul>
 * {@code x-amz-bucket-region} (sent with region errors and redirects) is added as
 * {@code BucketRegion}. Redirects are never followed; a {@code 301 PermanentRedirect} becomes an
 * exception carrying the {@code Endpoint}.
 */
final class ErrorMapper {

  static final String BUCKET_REGION = "BucketRegion";
  static final int MAX_EXCERPT = 200;

  private ErrorMapper() {
  }

  static S3ServiceException toException(S3Response response) {
    ErrorDocument error = S3Xml.parseError(response.body);
    String requestId = firstNonEmpty(error == null ? null : error.requestId(), response.headers.get("x-amz-request-id"));
    String hostId = firstNonEmpty(error == null ? null : error.hostId(), response.headers.get("x-amz-id-2"));
    Map<String, String> details = new LinkedHashMap<>();
    if (error != null) {
      details.putAll(error.details());
    }
    String bucketRegion = response.headers.get("x-amz-bucket-region");
    if (bucketRegion != null) {
      details.putIfAbsent(BUCKET_REGION, bucketRegion);
    }
    return new S3ServiceException(message(response, error), response.statusCode,
      error == null ? null : emptyToNull(error.code()), requestId, hostId,
      error == null ? null : emptyToNull(error.resource()), details);
  }

  private static String message(S3Response response, ErrorDocument error) {
    String status = "HTTP " + response.statusCode + (response.statusMessage == null ? "" : " " + response.statusMessage);
    if (error != null) {
      String code = emptyToNull(error.code());
      String text = emptyToNull(error.message());
      if (code != null && text != null) {
        return code + ": " + text + " (" + status + ")";
      }
      if (code != null || text != null) {
        return (code != null ? code : text) + " (" + status + ")";
      }
    }
    String excerpt = excerpt(response);
    return excerpt == null ? status + " (no error document)" : status + ": " + excerpt;
  }

  /** The start of a non-XML body, with control characters removed, or {@code null} if empty. */
  private static String excerpt(S3Response response) {
    if (response.body == null || response.body.length() == 0) {
      return null;
    }
    int length = Math.min(response.body.length(), MAX_EXCERPT * 4);
    String text = new String(response.body.getBytes(0, length), StandardCharsets.UTF_8);
    StringBuilder clean = new StringBuilder();
    boolean space = false;
    for (int i = 0; i < text.length() && clean.length() < MAX_EXCERPT; i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || Character.isISOControl(c)) {
        space = clean.length() > 0;
      } else {
        if (space) {
          clean.append(' ');
          space = false;
        }
        clean.append(c);
      }
    }
    if (clean.length() == 0) {
      return null;
    }
    return clean.length() >= MAX_EXCERPT || response.body.length() > length ? clean + "..." : clean.toString();
  }

  private static String firstNonEmpty(String a, String b) {
    return emptyToNull(a) != null ? a : emptyToNull(b);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
