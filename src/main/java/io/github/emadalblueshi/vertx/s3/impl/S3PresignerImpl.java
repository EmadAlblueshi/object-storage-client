package io.github.emadalblueshi.vertx.s3.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignGetOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignUploadPartOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
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
    return presign("GET", bucket, key, seconds(expiry, unit), List.of(), List.of()).getUrl();
  }

  @Override
  public String presignPut(String bucket, String key, long expiry, TimeUnit unit) {
    return presign("PUT", bucket, key, seconds(expiry, unit), List.of(), List.of()).getUrl();
  }

  @Override
  public String presignGet(String bucket, String key, Duration expiry) {
    return presign("GET", bucket, key, seconds(expiry), List.of(), List.of()).getUrl();
  }

  @Override
  public String presignPut(String bucket, String key, Duration expiry) {
    return presign("PUT", bucket, key, seconds(expiry), List.of(), List.of()).getUrl();
  }

  @Override
  public S3PresignedRequest presignPut(String bucket, String key, long expiry, TimeUnit unit, S3PresignPutOptions options) {
    return presign("PUT", bucket, key, seconds(expiry, unit), List.of(), signedHeaders(options));
  }

  @Override
  public S3PresignedRequest presignGet(String bucket, String key, long expiry, TimeUnit unit, S3PresignGetOptions options) {
    return presign("GET", bucket, key, seconds(expiry, unit), responseOverrides(options), List.of());
  }

  @Override
  public S3PresignedRequest presignPut(String bucket, String key, Duration expiry, S3PresignPutOptions options) {
    return presign("PUT", bucket, key, seconds(expiry), List.of(), signedHeaders(options));
  }

  @Override
  public S3PresignedRequest presignGet(String bucket, String key, Duration expiry, S3PresignGetOptions options) {
    return presign("GET", bucket, key, seconds(expiry), responseOverrides(options), List.of());
  }

  @Override
  public String presignUploadPart(String bucket, String key, String uploadId, int partNumber, long expiry, TimeUnit unit) {
    return uploadPart(bucket, key, uploadId, partNumber, seconds(expiry, unit), null).getUrl();
  }

  @Override
  public S3PresignedRequest presignUploadPart(String bucket, String key, String uploadId, int partNumber, long expiry,
                                              TimeUnit unit, S3PresignUploadPartOptions options) {
    return uploadPart(bucket, key, uploadId, partNumber, seconds(expiry, unit), options);
  }

  @Override
  public String presignUploadPart(String bucket, String key, String uploadId, int partNumber, Duration expiry) {
    return uploadPart(bucket, key, uploadId, partNumber, seconds(expiry), null).getUrl();
  }

  @Override
  public S3PresignedRequest presignUploadPart(String bucket, String key, String uploadId, int partNumber, Duration expiry,
                                              S3PresignUploadPartOptions options) {
    return uploadPart(bucket, key, uploadId, partNumber, seconds(expiry), options);
  }

  /** UploadPart: a PUT with the part number and upload id as signed query parameters. */
  private S3PresignedRequest uploadPart(String bucket, String key, String uploadId, int partNumber, long expirySeconds,
                                        S3PresignUploadPartOptions options) {
    Objects.requireNonNull(uploadId, "uploadId");
    String problem = MultipartRequests.uploadIdProblem(uploadId);
    if (problem == null) {
      problem = MultipartRequests.partNumberProblem(partNumber);
    }
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    if (options != null && options.getContentMd5() != null) {
      headers.add(Map.entry("Content-MD5", contentMd5(options.getContentMd5())));
    }
    if (options != null && options.getContentLength() != null) {
      headers.add(Map.entry("Content-Length", contentLength(options.getContentLength(), MultipartRequests.MAX_PART_SIZE,
        "A part is limited to 5 GiB")));
    }
    return presign("PUT", bucket, key, expirySeconds,
      List.of(Map.entry("partNumber", Integer.toString(partNumber)), Map.entry("uploadId", uploadId)), headers);
  }

  /**
   * @param query signed query parameters (the {@code response-*} overrides, or the part number and upload id)
   * @param headers signed headers the request must send
   */
  private S3PresignedRequest presign(String method, String bucket, String key, long expirySeconds,
                                     List<Map.Entry<String, String>> query, List<Map.Entry<String, String>> headers) {
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
      new S3SignatureV4.Request(method, host, path, query, headers, S3SignatureV4.UNSIGNED_PAYLOAD),
      expirySeconds, config.credentials.getAccessKey(), config.credentials.getSecretKey(),
      config.credentials.getSessionToken());
    Map<String, String> toSend = new LinkedHashMap<>();
    headers.forEach(header -> toSend.put(header.getKey(), header.getValue()));
    return new S3PresignedRequest()
      .setUrl((config.publicTls ? "https://" : "http://") + host + S3SignatureV4.encodePath(path) + "?" + presigned.query)
      .setMethod(method)
      .setHeaders(toSend);
  }

  /**
   * The headers to sign for {@code options} ({@code null} means none), always in this order:
   * {@code Content-Type}, {@code Content-MD5}, {@code Content-Length}. Throws
   * {@link IllegalArgumentException}.
   */
  static List<Map.Entry<String, String>> signedHeaders(S3PresignPutOptions options) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    if (options == null) {
      return headers;
    }
    if (options.getContentType() != null) {
      headers.add(Map.entry("Content-Type", headerValue(options.getContentType(), "content type")));
    }
    if (options.getContentMd5() != null) {
      headers.add(Map.entry("Content-MD5", contentMd5(options.getContentMd5())));
    }
    if (options.getContentLength() != null) {
      headers.add(Map.entry("Content-Length", contentLength(options.getContentLength(), MultipartRequests.MAX_PUT_SIZE,
        "A single PUT is limited to 5 GiB")));
    }
    return headers;
  }

  /** The {@code response-*} query parameters for {@code options} ({@code null} means none); throws {@link IllegalArgumentException}. */
  static List<Map.Entry<String, String>> responseOverrides(S3PresignGetOptions options) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    if (options == null) {
      return query;
    }
    if (options.getContentType() != null) {
      query.add(Map.entry("response-content-type", headerValue(options.getContentType(), "content type")));
    }
    if (options.getContentDisposition() != null) {
      if (!ObjectRequests.isHeaderValue(options.getContentDisposition())) {
        throw new IllegalArgumentException("The content disposition must be non-empty printable US-ASCII; for a "
          + "non-ASCII file name use S3PresignGetOptions.attachment or inline");
      }
      query.add(Map.entry("response-content-disposition", options.getContentDisposition()));
    }
    if (options.getCacheControl() != null) {
      query.add(Map.entry("response-cache-control", headerValue(options.getCacheControl(), "cache control")));
    }
    return query;
  }

  private static String headerValue(String value, String name) {
    if (!ObjectRequests.isHeaderValue(value)) {
      throw new IllegalArgumentException("The " + name + " must be non-empty printable US-ASCII");
    }
    return value;
  }

  /** A {@code Content-Length} value: 0 to {@code max} bytes, in decimal. */
  private static String contentLength(long length, long max, String limit) {
    if (length < 0) {
      throw new IllegalArgumentException("The content length must not be negative: " + length);
    }
    if (length > max) {
      throw new IllegalArgumentException(limit + " (" + max + " bytes): " + length
        + (max == MultipartRequests.MAX_PUT_SIZE ? "; use a multipart upload" : ""));
    }
    return Long.toString(length);
  }

  /** A {@code Content-MD5} value: the canonical, padded base64 of exactly 16 bytes. */
  private static String contentMd5(String value) {
    byte[] digest;
    try {
      digest = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      digest = null;
    }
    if (digest == null || digest.length != 16 || !Base64.getEncoder().encodeToString(digest).equals(value)) {
      throw new IllegalArgumentException("The Content-MD5 must be the base64 encoding (with padding) of a 16-byte MD5 digest");
    }
    return value;
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
