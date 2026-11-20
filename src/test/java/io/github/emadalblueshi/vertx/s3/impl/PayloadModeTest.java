package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The payload-signing decision (decision 4, Option A): the endpoint scheme decides. */
class PayloadModeTest {

  private static final int LIMIT = PayloadMode.MAX_INLINE_HASH_SIZE;

  @Test
  void httpsIsAlwaysUnsigned() {
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(true, -1));
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(true, 0));
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(true, LIMIT + 1));
    assertEquals(PayloadMode.UNSIGNED, PayloadMode.decide(true, 5L * 1024 * 1024 * 1024));
  }

  @Test
  void httpIsAlwaysSigned() {
    assertEquals(PayloadMode.EMPTY, PayloadMode.decide(false, -1));
    assertEquals(PayloadMode.INLINE, PayloadMode.decide(false, 0));
    assertEquals(PayloadMode.INLINE, PayloadMode.decide(false, LIMIT));
    assertEquals(PayloadMode.OFFLOAD, PayloadMode.decide(false, LIMIT + 1));
  }

  @Test
  void inlineLimitIs256KiB() {
    assertEquals(256 * 1024, LIMIT);
  }
}
