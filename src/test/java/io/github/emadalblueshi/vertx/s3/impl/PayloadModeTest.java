package io.github.emadalblueshi.vertx.s3.impl;

import static io.github.emadalblueshi.vertx.s3.S3PayloadSigning.AUTO;
import static io.github.emadalblueshi.vertx.s3.S3PayloadSigning.SIGNED;
import static io.github.emadalblueshi.vertx.s3.S3PayloadSigning.UNSIGNED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The buffered-payload signing decision (decision 4). */
class PayloadModeTest {

  private static final int LIMIT = 1024;

  @Test
  void noBodyIsAlwaysTheEmptyHash() {
    for (boolean tls : new boolean[] { false, true }) {
      assertEquals(PayloadMode.EMPTY, PayloadMode.decide(AUTO, tls, -1, LIMIT));
      assertEquals(PayloadMode.EMPTY, PayloadMode.decide(SIGNED, tls, -1, LIMIT));
      assertEquals(PayloadMode.EMPTY, PayloadMode.decide(UNSIGNED, tls, -1, LIMIT));
    }
  }

  @Test
  void bufferedBodiesAreSignedUnderAutoAndSigned() {
    for (boolean tls : new boolean[] { false, true }) {
      assertEquals(PayloadMode.INLINE, PayloadMode.decide(AUTO, tls, 0, LIMIT));
      assertEquals(PayloadMode.INLINE, PayloadMode.decide(AUTO, tls, LIMIT, LIMIT));
      assertEquals(PayloadMode.OFFLOAD, PayloadMode.decide(AUTO, tls, LIMIT + 1, LIMIT));
      assertEquals(PayloadMode.INLINE, PayloadMode.decide(SIGNED, tls, 10, LIMIT));
      assertEquals(PayloadMode.OFFLOAD, PayloadMode.decide(SIGNED, tls, LIMIT + 1, LIMIT));
    }
  }

  @Test
  void unsignedOnlyOverHttps() {
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(UNSIGNED, true, 10, LIMIT));
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(UNSIGNED, true, LIMIT + 1, LIMIT));
    // Unreachable in practice (create() rejects UNSIGNED over http), but never unsigned over HTTP.
    assertEquals(PayloadMode.INLINE, PayloadMode.decide(UNSIGNED, false, 10, LIMIT));
  }

  @Test
  void zeroLimitOffloadsEveryNonEmptyBody() {
    assertEquals(PayloadMode.INLINE, PayloadMode.decide(AUTO, false, 0, 0));
    assertEquals(PayloadMode.OFFLOAD, PayloadMode.decide(AUTO, false, 1, 0));
  }
}
