package io.github.emadalblueshi.vertx.s3.impl;

import java.util.regex.Pattern;

/**
 * Bucket-name validation, done before any I/O.
 * <p>
 * The general rules are the S3 ones every compatible server shares: 3 to 63 characters, lowercase
 * letters, digits, dots and hyphens, starting and ending with a letter or digit, no two adjacent
 * dots, not an IPv4 address. AWS-only reserved prefixes and suffixes are left to the server.
 * Legacy names with uppercase letters or underscores (old us-east-1 buckets) are rejected.
 * <p>
 * Dotted names are also rejected with virtual-hosted-style access over HTTPS: {@code a.b.host}
 * doesn't match the server's {@code *.host} wildcard certificate, so the TLS handshake would fail
 * with a confusing error. Path-style access (the default) has no such limit.
 */
final class BucketNames {

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  private BucketNames() {
  }

  /**
   * @return why {@code bucket} can't be used with this configuration, or {@code null} if it can
   */
  static String problem(String bucket, ClientConfig config) {
    return problem(bucket, config.pathStyleAccess, config.tls);
  }

  /**
   * As {@link #problem(String, ClientConfig)}, for an explicit addressing style and scheme (the
   * presigner checks against its public endpoint's scheme).
   */
  static String problem(String bucket, boolean pathStyleAccess, boolean tls) {
    if (bucket == null) {
      return "The bucket name is required";
    }
    if (bucket.length() < 3 || bucket.length() > 63) {
      return invalid(bucket, "must be 3 to 63 characters long");
    }
    if (!NAME.matcher(bucket).matches()) {
      return invalid(bucket, "must contain only lowercase letters, digits, dots and hyphens, and start and end "
        + "with a letter or digit");
    }
    if (bucket.contains("..")) {
      return invalid(bucket, "must not contain two adjacent dots");
    }
    if (IPV4.matcher(bucket).matches()) {
      return invalid(bucket, "must not be formatted as an IP address");
    }
    if (bucket.indexOf('.') >= 0 && !pathStyleAccess && tls) {
      return invalid(bucket, "contains dots, which don't work with virtual-hosted-style access over HTTPS (the "
        + "server's wildcard certificate doesn't match); use pathStyleAccess = true");
    }
    return null;
  }

  private static String invalid(String bucket, String reason) {
    return "Invalid bucket name \"" + bucket + "\": " + reason;
  }
}
