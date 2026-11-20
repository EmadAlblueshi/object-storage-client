package io.github.emadalblueshi.vertx.s3.impl;

/**
 * How a request payload is covered by the signature (decision 4, Option A). Not configurable;
 * the endpoint scheme decides:
 * <ul>
 *   <li>HTTPS: {@code UNSIGNED-PAYLOAD} for every request. TLS protects the body in transit; the
 *   signature still covers method, path, query and headers. Nothing is hashed.</li>
 *   <li>HTTP: the payload is always signed with its SHA-256. Bodies up to
 *   {@link #MAX_INLINE_HASH_SIZE} are hashed inline; larger ones via {@code executeBlocking},
 *   never on the event loop.</li>
 * </ul>
 */
enum PayloadMode {

  /** HTTP, no body: the SHA-256 of the empty string. */
  EMPTY,
  /** HTTP: signed; hashed inline on the caller's thread (small body). */
  INLINE,
  /** HTTP: signed; hashed via {@code executeBlocking}, never on the event loop (large body). */
  OFFLOAD,
  /** HTTPS: {@code UNSIGNED-PAYLOAD}. */
  UNSIGNED;

  /** The largest body (in bytes) hashed inline over HTTP: 256 KiB, well under a millisecond of SHA-256. */
  static final int MAX_INLINE_HASH_SIZE = 256 * 1024;

  /**
   * @param tls whether the endpoint is HTTPS
   * @param bodyLength the buffered body length, or {@code -1} for no body
   */
  static PayloadMode decide(boolean tls, long bodyLength) {
    if (tls) {
      return UNSIGNED;
    }
    if (bodyLength < 0) {
      return EMPTY;
    }
    return bodyLength <= MAX_INLINE_HASH_SIZE ? INLINE : OFFLOAD;
  }
}
