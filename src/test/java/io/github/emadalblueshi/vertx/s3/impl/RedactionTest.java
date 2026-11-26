package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3Credentials;

/** What {@link Redaction} replaces: the secret key, the session token, the Authorization value and its signature. */
class RedactionTest {

  private static final String SIGNATURE = "fe5f80f77d5fa3beca038a248ff027d0445342fe2855ddc963176630326f1024";
  private static final String AUTHORIZATION = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
    + "aws4_request, SignedHeaders=host;range;x-amz-date, Signature=" + SIGNATURE;
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
  private static final String TOKEN = "session+token/value==";
  private static final S3Credentials CREDENTIALS = new S3Credentials("AKIAIOSFODNN7EXAMPLE", SECRET_KEY)
    .setSessionToken(TOKEN);

  @Test
  void everySecretIsReplacedAndTheAccessKeyStays() {
    assertEquals("[REDACTED], [REDACTED], [REDACTED] and [REDACTED]; AKIAIOSFODNN7EXAMPLE stays",
      Redaction.of(CREDENTIALS, AUTHORIZATION).scrub(AUTHORIZATION + ", " + SIGNATURE + ", " + TOKEN + " and " + SECRET_KEY
        + "; AKIAIOSFODNN7EXAMPLE stays"));
  }

  @Test
  void aWholeAuthorizationValueBecomesOneRedaction() {
    assertEquals("Authorization: [REDACTED]", Redaction.of(CREDENTIALS, AUTHORIZATION).scrub("Authorization: " + AUTHORIZATION));
  }

  @Test
  void aReformattedAuthorizationValueStillLosesItsSignature() {
    String reformatted = AUTHORIZATION.replace(", ", ",");
    assertEquals(reformatted.replace(SIGNATURE, "[REDACTED]"), Redaction.of(CREDENTIALS, AUTHORIZATION).scrub(reformatted));
  }

  @Test
  void theSignatureIsTheLastPartOfTheAuthorizationValue() {
    assertEquals(SIGNATURE, Redaction.signature(AUTHORIZATION));
    assertNull(Redaction.signature(null));
    assertNull(Redaction.signature("Basic dXNlcjpwYXNz"));
    assertNull(Redaction.signature("AWS4-HMAC-SHA256 Credential=a/b, SignedHeaders=host, Signature="));
  }

  @Test
  void onlyTheSecretsThereAreAreReplaced() {
    assertEquals("text", Redaction.NONE.scrub("text"));
    assertNull(Redaction.NONE.scrub(null));
    assertEquals("a token stays, the [REDACTED] goes",
      Redaction.of(new S3Credentials("AKID", "secret"), null).scrub("a token stays, the secret goes"));
  }

  @Test
  void appearsIn() {
    Redaction redaction = Redaction.of(CREDENTIALS, AUTHORIZATION);
    assertTrue(redaction.appearsIn("x" + SIGNATURE + "y"));
    assertTrue(redaction.appearsIn(TOKEN));
    assertFalse(redaction.appearsIn("AKIAIOSFODNN7EXAMPLE"));
    assertFalse(Redaction.NONE.appearsIn("anything"));
  }
}
