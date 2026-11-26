package io.github.emadalblueshi.vertx.s3.backend;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.testcontainers.containers.Container;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.PemTrustOptions;

/**
 * {@link S3TestBackend} backed by a {@link SiloContainer}.
 */
final class SiloBackend implements S3TestBackend {

  /** Where the server moves the data of deleted objects until it purges them. */
  private static final String TRASH = SiloContainer.DATA_DIR + "/.minio.sys/tmp/.trash";
  private static final String PROBE = TRASH + "/purge-probe";

  private final SiloContainer container;
  private final boolean purgeEvery5s;

  SiloBackend(boolean tls) {
    this.container = new SiloContainer(tls);
    this.purgeEvery5s = false;
  }

  /**
   * With the server's data on a tmpfs of {@code dataMiB}, and the data of deleted objects purged
   * every 5 s instead of every 5 min: a long run creating and deleting large objects would
   * otherwise fill the tmpfs with it. The server arms its first purge before it reads that
   * setting, so the first one comes 5 min after it starts: {@link #start()} waits for it.
   */
  SiloBackend(boolean tls, int dataMiB) {
    this.container = new SiloContainer(tls);
    this.purgeEvery5s = true;
    container.withTmpFs(Map.of(SiloContainer.DATA_DIR, "rw,size=" + dataMiB + "m"))
      .withEnv("MINIO_API_DELETE_CLEANUP_INTERVAL", "5s");
  }

  @Override
  public String name() {
    return container.tls() ? "silo-tls" : "silo";
  }

  @Override
  public void start() {
    container.start();
    if (purgeEvery5s) {
      // The first purge, 5 min after start-up; then the next, which proves the 5 s interval
      awaitPurge(Duration.ofMinutes(8));
      awaitPurge(Duration.ofSeconds(30));
    }
  }

  /** Puts a directory into the server's trash and waits for the server to purge it. */
  private void awaitPurge(Duration timeout) {
    exec("mkdir -p " + PROBE);
    long deadline = System.nanoTime() + timeout.toNanos();
    while (exec("test -e " + PROBE + " && echo present || echo purged").equals("present")) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("Silo didn't purge its trash within " + timeout);
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private String exec(String command) {
    try {
      Container.ExecResult result = container.execInContainer("sh", "-c", command);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException("'" + command + "' failed in the Silo container: " + result.getStderr());
      }
      return result.getStdout().trim();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
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
