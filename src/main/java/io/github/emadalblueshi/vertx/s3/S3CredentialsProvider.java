package io.github.emadalblueshi.vertx.s3;

import java.util.Objects;

/**
 * Supplies the current {@link S3Credentials}, e.g. to pick up rotated Kubernetes secrets.
 * <p>
 * It is synchronous on purpose: presigning is pure computation and needs credentials without a
 * {@code Future}. The client calls it for every request, possibly on an event loop and from
 * several threads, so implementations must be thread-safe, non-blocking and cheap. Typically
 * they return a value held in a {@code volatile} field that a separate watcher updates.
 */
@FunctionalInterface
public interface S3CredentialsProvider {

  /**
   * A provider that always returns a copy of {@code credentials}, as they are now. Later changes
   * to the argument are not seen.
   */
  static S3CredentialsProvider of(S3Credentials credentials) {
    S3Credentials snapshot = new S3Credentials(Objects.requireNonNull(credentials, "credentials"));
    return () -> new S3Credentials(snapshot);
  }

  /** The current credentials. Must not block, and must not return {@code null}. */
  S3Credentials credentials();
}
