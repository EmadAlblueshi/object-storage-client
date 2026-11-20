package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserAgentTest {

  @Test
  void carriesTheBuildVersion() {
    // Filtered from the pom at build time; "unknown" would mean the resource wasn't filtered
    assertTrue(UserAgent.VALUE.matches("vertx-s3-client/\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), UserAgent.VALUE);
  }
}
