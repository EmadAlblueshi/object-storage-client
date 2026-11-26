package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.emadalblueshi.vertx.s3.S3ServiceException;

/**
 * Checks that a text a log could show (what was logged, an exception) contains no secret. A failure
 * message never shows the secret it found: secrets are masked and long token-like runs are cut out
 * of the context, since against a real account the secrets are real.
 */
final class Leaks {

  /**
   * Text that only appears where credentials are: an {@code Authorization} value, a presigned query,
   * the security token header. Logged, it means credentials were logged.
   */
  static final List<String> LOG_MARKERS = List.of("AWS4-HMAC-SHA256", "Credential=", "Signature=", "X-Amz-Signature",
    "X-Amz-Credential", "X-Amz-Security-Token");

  /** Query parameters of a presigned URL: the client never sends one, so no exception may contain them. */
  static final List<String> PRESIGNED_QUERY_MARKERS = List.of("X-Amz-Signature=", "X-Amz-Credential=");

  /** A secret's fragment this long (or longer) counts as a leak, e.g. a token cut by an excerpt. */
  static final int FRAGMENT = 16;

  private Leaks() {
  }

  /**
   * What a log shows of {@code error}: its stack trace, with causes and suppressed exceptions, plus
   * the fields of every {@link S3ServiceException} among them.
   */
  static String asLogged(Throwable error) {
    StringWriter text = new StringWriter();
    error.printStackTrace(new PrintWriter(text, true));
    appendServiceFields(error, text, Collections.newSetFromMap(new IdentityHashMap<>()));
    return text.toString();
  }

  private static void appendServiceFields(Throwable error, StringWriter text, Set<Throwable> seen) {
    if (error == null || !seen.add(error)) {
      return;
    }
    if (error instanceof S3ServiceException) {
      S3ServiceException service = (S3ServiceException) error;
      text.append("\ncode=").append(service.getErrorCode()).append(", requestId=").append(service.getRequestId())
        .append(", hostId=").append(service.getHostId()).append(", resource=").append(service.getResource())
        .append(", details=").append(String.valueOf(service.getDetails()));
    }
    appendServiceFields(error.getCause(), text, seen);
    for (Throwable suppressed : error.getSuppressed()) {
      appendServiceFields(suppressed, text, seen);
    }
  }

  /**
   * Fails if {@code text} contains one of {@code secrets}, or a fragment of one of at least
   * {@value #FRAGMENT} characters, or (ignoring case) one of {@code markers}.
   *
   * @param what what the text is, for the failure message
   */
  static void assertNoLeak(String what, String text, Collection<String> secrets, Collection<String> markers) {
    for (String secret : secrets) {
      if (secret == null || secret.isEmpty()) {
        continue;
      }
      int length = Math.min(FRAGMENT, secret.length());
      for (int start = 0; start + length <= secret.length(); start++) {
        String fragment = secret.substring(start, start + length);
        int at = text.indexOf(fragment);
        if (at >= 0) {
          fail(what + " contains a secret" + (length < secret.length() ? " (a fragment of one)" : "") + " at " + at
            + ": " + context(text, at, secrets));
        }
      }
    }
    String lowerCase = text.toLowerCase(Locale.ROOT);
    for (String marker : markers) {
      int at = lowerCase.indexOf(marker.toLowerCase(Locale.ROOT));
      if (at >= 0) {
        fail(what + " contains '" + marker + "' at " + at + ": " + context(text, at, secrets));
      }
    }
  }

  /** The text around {@code at}, with every secret masked and long token-like runs cut out. */
  private static String context(String text, int at, Collection<String> secrets) {
    String around = text.substring(Math.max(0, at - 80), Math.min(text.length(), at + 80));
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) {
        around = around.replace(secret, "<secret>");
      }
    }
    return around.replaceAll("[A-Za-z0-9+/=_-]{12,}", "<cut>").replace('\n', ' ');
  }
}
