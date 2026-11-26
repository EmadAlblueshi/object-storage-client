package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>
 * The exception never contains the client's secret key or session token, or the request's
 * {@code Authorization} value or signature ({@link S3Response#redaction}): wherever the response
 * echoes one, in any field, it is replaced with {@value Redaction#REDACTED}. The fields AWS adds to
 * signature errors ({@link #SIGNATURE_DEBUG_FIELDS}) are left out of the details.
 */
final class ErrorMapper {

  static final String BUCKET_REGION = "BucketRegion";
  static final int MAX_EXCERPT = 200;

  /**
   * The fields AWS adds to a signature error to help debug a signer. They echo the request: the
   * access key id, the signature, and in the canonical request every signed header value, the
   * session token included. Left out of the details, whatever their case.
   */
  static final Set<String> SIGNATURE_DEBUG_FIELDS = Set.of("AWSAccessKeyId", "StringToSign", "StringToSignBytes",
    "SignatureProvided", "CanonicalRequest", "CanonicalRequestBytes");

  private static final Set<String> SIGNATURE_DEBUG_FIELDS_LOWER_CASE = SIGNATURE_DEBUG_FIELDS.stream()
    .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

  private ErrorMapper() {
  }

  static S3ServiceException toException(S3Response response) {
    Redaction redaction = response.redaction;
    ErrorDocument error = S3Xml.parseError(response.body);
    String requestId = firstNonEmpty(error == null ? null : error.requestId(), response.headers.get("x-amz-request-id"));
    String hostId = firstNonEmpty(error == null ? null : error.hostId(), response.headers.get("x-amz-id-2"));
    Map<String, String> details = new LinkedHashMap<>();
    if (error != null) {
      error.details().forEach((name, value) -> {
        if (!SIGNATURE_DEBUG_FIELDS_LOWER_CASE.contains(name.toLowerCase(Locale.ROOT)) && !redaction.appearsIn(name)) {
          details.put(name, redaction.scrub(value));
        }
      });
    }
    String bucketRegion = response.headers.get("x-amz-bucket-region");
    if (bucketRegion != null) {
      details.putIfAbsent(BUCKET_REGION, redaction.scrub(bucketRegion));
    }
    return new S3ServiceException(message(response, error), response.statusCode,
      redaction.scrub(error == null ? null : emptyToNull(error.code())), redaction.scrub(requestId),
      redaction.scrub(hostId), redaction.scrub(error == null ? null : emptyToNull(error.resource())), details);
  }

  private static String message(S3Response response, ErrorDocument error) {
    Redaction redaction = response.redaction;
    String status = "HTTP " + response.statusCode + (response.statusMessage == null ? "" : " " + response.statusMessage);
    if (error != null) {
      String code = emptyToNull(error.code());
      String text = emptyToNull(error.message());
      if (code != null && text != null) {
        return redaction.scrub(code + ": " + text + " (" + status + ")");
      }
      if (code != null || text != null) {
        return redaction.scrub((code != null ? code : text) + " (" + status + ")");
      }
    }
    String excerpt = excerpt(response);
    return redaction.scrub(status) + (excerpt == null ? " (no error document)" : ": " + excerpt);
  }

  /**
   * The start of a non-XML body, with control characters removed, or {@code null} if empty. Secrets
   * are replaced in the whole body (at most the transport's buffered limit) before it is cut, so
   * the cut can't leave a visible prefix of one.
   */
  private static String excerpt(S3Response response) {
    if (response.body == null || response.body.length() == 0) {
      return null;
    }
    String text = response.redaction.scrub(response.body.toString(StandardCharsets.UTF_8));
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
    return clean.length() >= MAX_EXCERPT ? clean + "..." : clean.toString();
  }

  private static String firstNonEmpty(String a, String b) {
    return emptyToNull(a) != null ? a : emptyToNull(b);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
