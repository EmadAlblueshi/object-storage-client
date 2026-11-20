package io.github.emadalblueshi.vertx.s3.backend;

import java.net.URI;

import io.vertx.core.http.HttpClientOptions;

/**
 * An S3-compatible server that integration tests run against.
 * <p>
 * Tests depend only on this abstraction (endpoint, region, credentials, TLS), never on a
 * concrete container, so more backends can be added later (e.g. SeaweedFS, or a DigitalOcean
 * Spaces profile whose credentials come from the environment).
 * <p>
 * {@link #start()} and {@link #stop()} block. Call them from the JUnit thread (for example in
 * {@code @BeforeAll}/{@code @AfterAll}), never from a Vert.x event loop.
 * <p>
 * Deliberately <em>not</em> {@link AutoCloseable}: JUnit closes {@code AutoCloseable} arguments
 * of a {@code @ParameterizedTest} after each invocation, which would stop a shared backend after
 * the first test.
 */
public interface S3TestBackend {

  /** Silo (MinIO-compatible) over plain HTTP. */
  static S3TestBackend silo() {
    return new SiloBackend(false);
  }

  /** Silo (MinIO-compatible) over HTTPS with the test-only self-signed certificate. */
  static S3TestBackend siloTls() {
    return new SiloBackend(true);
  }

  /**
   * A Silo backend over plain HTTP, shared by all test classes in the JVM: started on first use
   * and never stopped by tests (Testcontainers' Ryuk removes it when the JVM exits). Tests must
   * use unique bucket names. Never call {@link #stop()} on it.
   */
  static S3TestBackend sharedSilo() {
    return SharedBackends.SILO.get();
  }

  /** Like {@link #sharedSilo()}, over HTTPS with the test-only self-signed certificate. */
  static S3TestBackend sharedSiloTls() {
    return SharedBackends.SILO_TLS.get();
  }

  /** Short name used in test display names, e.g. {@code silo-tls}. */
  String name();

  /** Starts the backend. Blocking. */
  void start();

  /** Stops the backend and releases its resources. Blocking. */
  void stop();

  /** Endpoint as {@code scheme://host:port}, e.g. {@code https://localhost:49153}. */
  URI endpoint();

  /** Region the backend is configured with. */
  String region();

  String accessKey();

  String secretKey();

  /** Whether {@link #endpoint()} is HTTPS. */
  boolean tls();

  /**
   * Options for a plain Vert.x {@code HttpClient} that reaches this backend: default host and
   * port, and for TLS the trust for the backend certificate (host verification stays enabled).
   * Returns a new instance on every call.
   */
  HttpClientOptions httpClientOptions();
}
