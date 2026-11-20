package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SHA-256, HMAC-SHA256 and hex helpers for SigV4.
 * <p>
 * Ported from {@code objectstorage.client.s3.impl.HashUtil} (commit {@code df34dc8}), with these fixes:
 * <ul>
 *   <li>No provider lookup per call. A prototype {@link MessageDigest}/{@link Mac} is looked up
 *   once and {@code clone()}d for every use. These classes are not thread-safe, so an instance
 *   is never shared. Cloning (rather than a {@code ThreadLocal}) also works well on virtual
 *   threads, where thread-locals don't get reused.</li>
 *   <li>Hex encoding through a lookup table instead of {@code String.format} per byte.</li>
 * </ul>
 */
final class HashUtil {

  private static final String SHA_256 = "SHA-256";
  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final MessageDigest SHA_256_PROTOTYPE = newSha256();
  private static final Mac HMAC_SHA_256_PROTOTYPE = newHmacSha256();

  private HashUtil() {
  }

  /** A fresh, unshared SHA-256 digest. */
  static MessageDigest sha256() {
    try {
      return (MessageDigest) SHA_256_PROTOTYPE.clone();
    } catch (CloneNotSupportedException e) {
      return newSha256();
    }
  }

  /** A fresh, unshared HMAC-SHA256 instance initialised with {@code key}. */
  static Mac hmacSha256(byte[] key) {
    Mac mac;
    try {
      mac = (Mac) HMAC_SHA_256_PROTOTYPE.clone();
    } catch (CloneNotSupportedException e) {
      mac = newHmacSha256();
    }
    try {
      mac.init(new SecretKeySpec(key, HMAC_SHA_256));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot initialise " + HMAC_SHA_256, e);
    }
    return mac;
  }

  static byte[] sha256(byte[] input) {
    return sha256().digest(input);
  }

  static String sha256Hex(byte[] input) {
    return hex(sha256(input));
  }

  static String sha256Hex(String input) {
    return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
  }

  static byte[] hmacSha256(byte[] key, String input) {
    return hmacSha256(key).doFinal(input.getBytes(StandardCharsets.UTF_8));
  }

  static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xff;
      out[2 * i] = HEX[b >>> 4];
      out[2 * i + 1] = HEX[b & 0x0f];
    }
    return new String(out);
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance(SHA_256);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(SHA_256 + " not available", e);
    }
  }

  private static Mac newHmacSha256() {
    try {
      return Mac.getInstance(HMAC_SHA_256);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(HMAC_SHA_256 + " not available", e);
    }
  }
}
