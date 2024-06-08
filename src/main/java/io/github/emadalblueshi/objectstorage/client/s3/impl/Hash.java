package io.github.emadalblueshi.objectstorage.client.s3.impl;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class Hash {

  public static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte oneByte : bytes) {
      sb.append(String.format("%02x", oneByte));
    }
    return sb.toString();
  }

  public static byte[] sha256(byte[] input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(input);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

  }

  public static byte[] hmacSHA256(byte[] key, String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }

  }
}
