package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.S3Credentials;

/**
 * What an exception must never contain, for one request: the client's secret key and session
 * token, the request's {@code Authorization} value, and that value's signature on its own. A server
 * may echo them in an error response: AWS returns the session token as {@code Token-0} with
 * {@code ExpiredToken}, and a proxy's error page may quote the request's headers. So every text the
 * library takes from an error response goes through {@link #scrub}, which replaces each of them
 * with {@value #REDACTED}, whatever field it appears in. Immutable.
 */
final class Redaction {

  static final String REDACTED = "[REDACTED]";

  /** Nothing to replace: for responses that weren't sent by the transport (tests). */
  static final Redaction NONE = new Redaction(null, null, null);

  private final String secretKey;
  private final String sessionToken;
  private final String authorization;

  private Redaction(String secretKey, String sessionToken, String authorization) {
    this.secretKey = secretKey;
    this.sessionToken = sessionToken;
    this.authorization = authorization;
  }

  /**
   * @param credentials the client's credentials
   * @param authorization the request's {@code Authorization} header value, or {@code null}
   */
  static Redaction of(S3Credentials credentials, String authorization) {
    return new Redaction(credentials.getSecretKey(), credentials.getSessionToken(), authorization);
  }

  /**
   * {@code text} with every secret replaced by {@value #REDACTED}; {@code null} stays {@code null}.
   * The {@code Authorization} value is replaced before the signature it contains, so a whole echo
   * becomes one {@value #REDACTED}.
   */
  String scrub(String text) {
    if (text == null) {
      return null;
    }
    String result = replace(text, authorization);
    result = replace(result, signature(authorization));
    result = replace(result, sessionToken);
    return replace(result, secretKey);
  }

  /** Whether {@code text} contains one of the secrets. */
  boolean appearsIn(String text) {
    return !scrub(text).equals(text);
  }

  /** The signature of a SigV4 {@code Authorization} value (its last part, {@code Signature=...}), or {@code null}. */
  static String signature(String authorization) {
    if (authorization == null) {
      return null;
    }
    int at = authorization.lastIndexOf("Signature=");
    if (at < 0) {
      return null;
    }
    String signature = authorization.substring(at + "Signature=".length()).trim();
    return signature.isEmpty() ? null : signature;
  }

  private static String replace(String text, String secret) {
    return secret == null || secret.isEmpty() ? text : text.replace(secret, REDACTED);
  }
}
