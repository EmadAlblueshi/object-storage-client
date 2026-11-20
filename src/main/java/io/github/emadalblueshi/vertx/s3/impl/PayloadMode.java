package io.github.emadalblueshi.vertx.s3.impl;

import io.github.emadalblueshi.vertx.s3.S3PayloadSigning;

/**
 * How a buffered request payload is covered by the signature: the payload-signing policy
 * (decision 4) as a pure function. Streaming and multipart bodies (where {@code AUTO} means
 * {@code UNSIGNED-PAYLOAD} over HTTPS) are added in Phase 4.
 */
enum PayloadMode {

  /** No body: the SHA-256 of the empty string. */
  EMPTY,
  /** Signed; hashed inline on the caller's thread (small body). */
  INLINE,
  /** Signed; hashed via {@code executeBlocking}, never on the event loop (large body). */
  OFFLOAD,
  /** {@code UNSIGNED-PAYLOAD}. Only reachable over HTTPS: {@code create()} rejects {@code UNSIGNED} over HTTP. */
  UNSIGNED;

  /**
   * @param bodyLength the buffered body length, or {@code -1} for no body
   */
  static PayloadMode decide(S3PayloadSigning policy, boolean tls, long bodyLength, int maxInlinePayloadHashSize) {
    if (bodyLength < 0) {
      return EMPTY;
    }
    if (policy == S3PayloadSigning.UNSIGNED && tls) {
      return UNSIGNED;
    }
    return bodyLength <= maxInlinePayloadHashSize ? INLINE : OFFLOAD;
  }
}
