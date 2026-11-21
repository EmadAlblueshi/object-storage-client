package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

class S3CredentialsTest {

  private static final String ACCESS = "AKIAEXAMPLE";
  private static final String SECRET = "very-secret-value";
  private static final String TOKEN = "session-token-value";

  @Test
  void defaultsAreEmpty() {
    S3Credentials credentials = new S3Credentials();
    assertNull(credentials.getAccessKey());
    assertNull(credentials.getSecretKey());
    assertNull(credentials.getSessionToken());
  }

  @Test
  void copyConstructorCopiesEverything() {
    S3Credentials original = new S3Credentials(ACCESS, SECRET).setSessionToken(TOKEN);
    S3Credentials copy = new S3Credentials(original);
    original.setAccessKey("changed").setSecretKey("changed").setSessionToken("changed");
    assertEquals(ACCESS, copy.getAccessKey());
    assertEquals(SECRET, copy.getSecretKey());
    assertEquals(TOKEN, copy.getSessionToken());
  }

  @Test
  void fromJsonReadsSecrets() {
    S3Credentials credentials = new S3Credentials(new JsonObject()
      .put("accessKey", ACCESS).put("secretKey", SECRET).put("sessionToken", TOKEN));
    assertEquals(ACCESS, credentials.getAccessKey());
    assertEquals(SECRET, credentials.getSecretKey());
    assertEquals(TOKEN, credentials.getSessionToken());
  }

  @Test
  void toJsonOmitsSecrets() {
    JsonObject json = new S3Credentials(ACCESS, SECRET).setSessionToken(TOKEN).toJson();
    assertEquals(new JsonObject().put("accessKey", ACCESS), json);
    assertFalse(json.encode().contains(SECRET));
    assertFalse(json.encode().contains(TOKEN));
  }

  @Test
  void toStringMasksSecrets() {
    String text = new S3Credentials(ACCESS, SECRET).setSessionToken(TOKEN).toString();
    assertTrue(text.contains(ACCESS), text);
    assertFalse(text.contains(SECRET), text);
    assertFalse(text.contains(TOKEN), text);
    assertEquals("S3Credentials{accessKey=AKIAEXAMPLE, secretKey=****, sessionToken=null}",
      new S3Credentials(ACCESS, SECRET).toString());
  }
}
