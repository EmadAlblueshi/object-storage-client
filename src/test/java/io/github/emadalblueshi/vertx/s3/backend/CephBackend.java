package io.github.emadalblueshi.vertx.s3.backend;

import java.net.URI;
import java.util.function.UnaryOperator;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.PemTrustOptions;

/**
 * {@link S3TestBackend} backed by a {@link CephContainer}: Ceph RGW over HTTP or HTTPS. Opt-in:
 * tests using it are skipped, never failed, unless {@code S3_TEST_CEPH=true}. Both views share
 * one JVM-wide container ({@link S3TestBackend#sharedCeph()}, {@link S3TestBackend#sharedCephTls()}).
 */
public final class CephBackend implements S3TestBackend {

  /** The environment variable that enables the Ceph backend. */
  public static final String VARIABLE = "S3_TEST_CEPH";

  /** The {@code disabledReason} for {@code @EnabledIf} on Ceph test classes. */
  public static final String DISABLED_REASON = "Ceph backend not enabled: set S3_TEST_CEPH=true";

  private final CephContainer container;
  private final boolean tls;

  CephBackend(CephContainer container, boolean tls) {
    this.container = container;
    this.tls = tls;
  }

  /** Whether {@code S3_TEST_CEPH} is {@code true}. */
  public static boolean configured() {
    return configured(System::getenv);
  }

  static boolean configured(UnaryOperator<String> env) {
    return "true".equalsIgnoreCase(String.valueOf(env.apply(VARIABLE)).trim());
  }

  @Override
  public String name() {
    return tls ? "ceph-tls" : "ceph";
  }

  /** The shared container is started by {@link SharedBackends}. */
  @Override
  public void start() {
  }

  /** Never stopped by tests; Testcontainers' Ryuk removes it when the JVM exits. */
  @Override
  public void stop() {
  }

  @Override
  public URI endpoint() {
    return URI.create((tls ? "https" : "http") + "://" + container.getHost() + ":" + container.s3Port(tls));
  }

  @Override
  public String region() {
    return CephContainer.REGION;
  }

  @Override
  public String accessKey() {
    return CephContainer.ACCESS_KEY;
  }

  @Override
  public String secretKey() {
    return CephContainer.SECRET_KEY;
  }

  @Override
  public boolean tls() {
    return tls;
  }

  @Override
  public HttpClientOptions httpClientOptions() {
    HttpClientOptions options = new HttpClientOptions()
      .setDefaultHost(container.getHost())
      .setDefaultPort(container.s3Port(tls));
    if (tls) {
      options.setSsl(true).setTrustOptions(new PemTrustOptions().addCertPath(SiloContainer.TLS_CERT_RESOURCE));
    }
    return options;
  }

  @Override
  public String toString() {
    return name();
  }
}
