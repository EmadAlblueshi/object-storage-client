package io.github.emadalblueshi.vertx.s3.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The Ceph backend is opt-in: only {@code S3_TEST_CEPH=true} enables it. */
class CephBackendTest {

  @Test
  void onlyTrueEnablesTheBackend() {
    assertTrue(CephBackend.configured(Map.of("S3_TEST_CEPH", "true")::get));
    assertTrue(CephBackend.configured(Map.of("S3_TEST_CEPH", " TRUE ")::get));
    assertFalse(CephBackend.configured(Map.<String, String>of()::get));
    assertFalse(CephBackend.configured(Map.of("S3_TEST_CEPH", "")::get));
    assertFalse(CephBackend.configured(Map.of("S3_TEST_CEPH", "1")::get));
    assertFalse(CephBackend.configured(Map.of("S3_TEST_CEPH", "false")::get));
  }
}
