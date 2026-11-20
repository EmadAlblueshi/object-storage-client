package io.github.emadalblueshi.vertx.s3.backend;

import java.net.URI;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.PemTrustOptions;

/**
 * {@link S3TestBackend} backed by a {@link SiloContainer}.
 */
final class SiloBackend implements S3TestBackend {

  private final SiloContainer container;

  SiloBackend(boolean tls) {
    this.container = new SiloContainer(tls);
  }

  @Override
  public String name() {
    return container.tls() ? "silo-tls" : "silo";
  }

  @Override
  public void start() {
    container.start();
  }

  @Override
  public void stop() {
    container.stop();
  }

  @Override
  public URI endpoint() {
    return URI.create((container.tls() ? "https" : "http") + "://" + container.getHost() + ":" + container.s3Port());
  }

  @Override
  public String region() {
    return SiloContainer.REGION;
  }

  @Override
  public String accessKey() {
    return SiloContainer.ACCESS_KEY;
  }

  @Override
  public String secretKey() {
    return SiloContainer.SECRET_KEY;
  }

  @Override
  public boolean tls() {
    return container.tls();
  }

  @Override
  public HttpClientOptions httpClientOptions() {
    HttpClientOptions options = new HttpClientOptions()
      .setDefaultHost(container.getHost())
      .setDefaultPort(container.s3Port());
    if (container.tls()) {
      options
        .setSsl(true)
        .setTrustOptions(new PemTrustOptions().addCertPath(SiloContainer.TLS_CERT_RESOURCE));
    }
    return options;
  }

  @Override
  public String toString() {
    return name();
  }
}
