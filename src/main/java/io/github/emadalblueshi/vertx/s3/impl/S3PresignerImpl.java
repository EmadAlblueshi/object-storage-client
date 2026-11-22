package io.github.emadalblueshi.vertx.s3.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Presigner;

/**
 * {@link S3Presigner} on the shared {@link S3SignatureV4}. Public only because
 * {@code S3Presigner.create} instantiates it (allowlisted in {@code PackagePolicyTest}, like
 * {@link S3ClientImpl}).
 */
public final class S3PresignerImpl implements S3Presigner {

  private final ClientConfig config;
  private final S3SignatureV4 signer;

  public S3PresignerImpl(S3ClientOptions options) {
    this(ClientConfig.from(options), Clock.systemUTC());
  }

  /** The test hook: presigns at the time {@code clock} gives. */
  S3PresignerImpl(ClientConfig config, Clock clock) {
    this(config, new S3SignatureV4(config.region, clock));
  }

  /** For {@link S3ClientImpl}, which shares its signer (one signing-key cache). */
  S3PresignerImpl(ClientConfig config, S3SignatureV4 signer) {
    this.config = config;
    this.signer = signer;
  }

  @Override
  public String presignGet(String bucket, String key, long expiry, TimeUnit unit) {
    return presign("GET", bucket, key, seconds(expiry, unit));
  }

  @Override
  public String presignPut(String bucket, String key, long expiry, TimeUnit unit) {
    return presign("PUT", bucket, key, seconds(expiry, unit));
  }

  @Override
  public String presignGet(String bucket, String key, Duration expiry) {
    return presign("GET", bucket, key, seconds(expiry));
  }

  @Override
  public String presignPut(String bucket, String key, Duration expiry) {
    return presign("PUT", bucket, key, seconds(expiry));
  }

  private String presign(String method, String bucket, String key, long expirySeconds) {
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(key, "key");
    String problem = BucketNames.problem(bucket, config.pathStyleAccess, config.publicTls);
    if (problem == null) {
      problem = ObjectRequests.keyProblem(key);
    }
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    String host = S3SignatureV4.hostHeader(config.publicHostFor(bucket), config.publicDefaultPort ? -1 : config.publicPort,
      config.publicTls);
    String path = config.pathFor(bucket, key);
    S3SignatureV4.Presigned presigned = signer.presign(
      new S3SignatureV4.Request(method, host, path, List.of(), List.of(), S3SignatureV4.UNSIGNED_PAYLOAD),
      expirySeconds, config.credentials.getAccessKey(), config.credentials.getSecretKey(),
      config.credentials.getSessionToken());
    return (config.publicTls ? "https://" : "http://") + host + S3SignatureV4.encodePath(path) + "?" + presigned.query;
  }

  /** The expiry in seconds: positive, a whole number of seconds, at most 7 days; never rounded. */
  static long seconds(long expiry, TimeUnit unit) {
    Objects.requireNonNull(unit, "unit");
    if (expiry <= 0) {
      throw new IllegalArgumentException("The expiry must be positive: " + expiry + " " + unit);
    }
    long seconds;
    if (unit.compareTo(TimeUnit.SECONDS) < 0) {
      long perSecond = unit.convert(1, TimeUnit.SECONDS);
      if (expiry % perSecond != 0) {
        throw new IllegalArgumentException("The expiry must be a whole number of seconds: " + expiry + " " + unit);
      }
      seconds = expiry / perSecond;
    } else {
      seconds = unit.toSeconds(expiry); // saturates at Long.MAX_VALUE
    }
    return withinLimit(seconds, expiry + " " + unit);
  }

  /** As {@link #seconds(long, TimeUnit)}, for a {@link Duration}. */
  static long seconds(Duration expiry) {
    Objects.requireNonNull(expiry, "expiry");
    if (expiry.isNegative() || expiry.isZero()) {
      throw new IllegalArgumentException("The expiry must be positive: " + expiry);
    }
    if (expiry.getNano() != 0) {
      throw new IllegalArgumentException("The expiry must be a whole number of seconds: " + expiry);
    }
    return withinLimit(expiry.getSeconds(), expiry.toString());
  }

  private static long withinLimit(long seconds, String expiry) {
    if (seconds > S3SignatureV4.MAX_PRESIGN_EXPIRY_SECONDS) {
      throw new IllegalArgumentException("The expiry must be at most 7 days (" + S3SignatureV4.MAX_PRESIGN_EXPIRY_SECONDS
        + " seconds): " + expiry);
    }
    return seconds;
  }
}
