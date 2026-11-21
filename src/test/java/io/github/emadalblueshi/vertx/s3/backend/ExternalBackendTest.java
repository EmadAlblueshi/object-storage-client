package io.github.emadalblueshi.vertx.s3.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/** How the external backend reads its configuration (no environment or network involved). */
class ExternalBackendTest {

  private static Map<String, String> complete() {
    Map<String, String> env = new HashMap<>();
    env.put("S3_ENDPOINT", "https://s3.example.com/");
    env.put("S3_REGION", "xx-test-1");
    env.put("S3_ACCESS_KEY", "access");
    env.put("S3_SECRET_KEY", "do-not-print-me");
    return env;
  }

  @Test
  void configuredOnlyWithAllFourVariables() {
    Map<String, String> env = complete();
    assertTrue(ExternalBackend.configured(env::get));
    env.put("S3_SECRET_KEY", " ");
    assertFalse(ExternalBackend.configured(env::get));
    TestAbortedException skipped = assertThrows(TestAbortedException.class, () -> ExternalBackend.fromVariables(env::get));
    assertTrue(skipped.getMessage().contains("set S3_SECRET_KEY"), skipped.getMessage());
  }

  @Test
  void readsTheVariablesAndDefaultsToPathStyle() {
    ExternalBackend backend = ExternalBackend.fromVariables(complete()::get);
    assertEquals("https://s3.example.com", backend.endpoint().toString());
    assertEquals("xx-test-1", backend.region());
    assertTrue(backend.tls());
    assertTrue(backend.pathStyleAccess());
    assertTrue(TestResources.clientOptions(backend).isPathStyleAccess());
    assertFalse(backend.toString().contains("do-not-print-me"), backend.toString());
  }

  @Test
  void pathStyleFalseSelectsVirtualHostedAddressing() {
    Map<String, String> env = complete();
    env.put("S3_PATH_STYLE", "FALSE");
    ExternalBackend backend = ExternalBackend.fromVariables(env::get);
    assertFalse(backend.pathStyleAccess());
    assertFalse(TestResources.clientOptions(backend).isPathStyleAccess());
    env.put("S3_PATH_STYLE", "true");
    assertTrue(ExternalBackend.fromVariables(env::get).pathStyleAccess());
  }

  @Test
  void invalidPathStyleValueFails() {
    Map<String, String> env = complete();
    env.put("S3_PATH_STYLE", "no");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ExternalBackend.fromVariables(env::get));
    assertEquals("S3_PATH_STYLE must be true or false, not \"no\"", e.getMessage());
  }
}
