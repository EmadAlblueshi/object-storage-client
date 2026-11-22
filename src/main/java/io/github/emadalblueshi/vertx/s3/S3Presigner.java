package io.github.emadalblueshi.vertx.s3;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.emadalblueshi.vertx.s3.impl.S3PresignerImpl;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.VertxGen;

/**
 * Creates presigned URLs: URLs that let anyone holding them download ({@code GET}) or upload
 * ({@code PUT}) one object until they expire, without credentials, e.g. from a browser or a
 * mobile app.
 * <p>
 * Presigning is pure computation with no I/O, so it is synchronous and needs no {@code Vertx}:
 * get a presigner from {@link S3Client#presigner()} or {@link #create(S3ClientOptions)}. It is
 * thread-safe, holds no resources and needs no closing.
 * <p>
 * URLs point to the {@linkplain S3ClientOptions#setPublicEndpoint public endpoint} when one is
 * set, otherwise to the endpoint, with the options' addressing style. They are signed with the
 * options' credentials (a session token is included) and the system clock; only the
 * {@code Host} header is signed, so an upload may send any {@code Content-Type}, which S3 stores.
 * <p>
 * The expiry is at most 7 days and a whole number of seconds. Invalid arguments throw
 * {@link IllegalArgumentException}, {@code null} arguments {@link NullPointerException}; the
 * bucket name and key rules are those of {@link S3Client}. Treat a URL like a password while it is
 * valid: don't log it.
 */
@VertxGen
public interface S3Presigner {

  /**
   * Creates a presigner, without a {@code Vertx} instance.
   *
   * @param options the options (copied; later changes have no effect); the transport settings are
   *     ignored
   * @return the presigner
   * @throws IllegalArgumentException if the options are invalid, as for {@link S3Client#create}
   * @throws NullPointerException if {@code options} is {@code null}
   */
  static S3Presigner create(S3ClientOptions options) {
    return new S3PresignerImpl(options);
  }

  /**
   * A URL to download an object ({@code GET}).
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param expiry how long the URL is valid, in {@code unit}: positive, a whole number of
   *     seconds, at most 7 days
   * @param unit the unit of {@code expiry}
   * @return the URL
   */
  String presignGet(String bucket, String key, long expiry, TimeUnit unit);

  /**
   * A URL to upload an object ({@code PUT}), replacing any existing object with that key.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param expiry how long the URL is valid, in {@code unit}: positive, a whole number of
   *     seconds, at most 7 days
   * @param unit the unit of {@code expiry}
   * @return the URL
   */
  String presignPut(String bucket, String key, long expiry, TimeUnit unit);

  /**
   * As {@link #presignGet(String, String, long, TimeUnit)}, with the expiry as a {@link Duration}.
   */
  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  String presignGet(String bucket, String key, Duration expiry);

  /**
   * As {@link #presignPut(String, String, long, TimeUnit)}, with the expiry as a {@link Duration}.
   */
  @GenIgnore(GenIgnore.PERMITTED_TYPE)
  String presignPut(String bucket, String key, Duration expiry);
}
