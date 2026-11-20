package io.github.emadalblueshi.vertx.s3;

/**
 * How request payloads are covered by the SigV4 signature ({@code X-Amz-Content-Sha256}).
 * <p>
 * Signed payloads are hashed with SHA-256. Bodies larger than
 * {@link S3ClientOptions#getMaxInlinePayloadHashSize()} are hashed via {@code executeBlocking},
 * never inline on the event loop.
 */
public enum S3PayloadSigning {

  /**
   * The default. Buffered requests are signed. Streaming and multipart uploads use
   * {@code UNSIGNED-PAYLOAD} over HTTPS (TLS protects integrity), and are signed over plain HTTP.
   */
  AUTO,

  /** Every payload is signed, including streaming and multipart uploads. */
  SIGNED,

  /**
   * Every payload uses {@code UNSIGNED-PAYLOAD}. HTTPS only: an {@code http://} endpoint with
   * this setting is rejected when the client is created.
   */
  UNSIGNED
}
