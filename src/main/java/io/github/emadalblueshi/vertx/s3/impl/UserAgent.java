package io.github.emadalblueshi.vertx.s3.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The {@code User-Agent} sent with every request: {@code vertx-s3-client/<version>}.
 * <p>
 * The version comes from {@code version.properties}, which Maven filters at build time. A
 * resource next to this class survives shading and works on both the class path and the module
 * path, unlike the jar manifest (replaced or merged by fat-jar packaging) or the module
 * descriptor (absent on the class path). It is read once, when the first client is created.
 */
final class UserAgent {

  static final String VALUE = "vertx-s3-client/" + version();

  private UserAgent() {
  }

  private static String version() {
    try (InputStream in = UserAgent.class.getResourceAsStream("version.properties")) {
      if (in == null) {
        return "unknown";
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version", "").trim();
      return version.isEmpty() || version.startsWith("${") ? "unknown" : version;
    } catch (IOException e) {
      return "unknown";
    }
  }
}
