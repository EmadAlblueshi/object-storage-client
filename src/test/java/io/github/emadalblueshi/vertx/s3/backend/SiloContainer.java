package io.github.emadalblueshi.vertx.s3.backend;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Testcontainers wrapper for <a href="https://hub.docker.com/r/pgsty/silo">Silo</a>, a maintained
 * MinIO fork with the same S3 API, {@code MINIO_*} environment variables and
 * {@code /minio/health} routes.
 * <p>
 * The image tag is pinned to an exact release. Never use {@code latest}, and don't change the
 * tag without the owner's approval.
 */
public class SiloContainer extends GenericContainer<SiloContainer> {

  public static final DockerImageName IMAGE =
    DockerImageName.parse("docker.io/pgsty/silo").withTag("RELEASE.2026-11-12T00-00-00Z");

  static final int S3_PORT = 9000;

  // Test-only credentials for a throwaway container.
  static final String ACCESS_KEY = "minioadmin";
  static final String SECRET_KEY = "minioadmin";

  // A non-default region, so region handling is actually exercised. Set via MINIO_SITE_REGION,
  // which makes the server enforce it: bucket-level requests signed for another region are
  // rejected (400 AuthorizationHeaderMalformed). ListBuckets and GetBucketLocation accept any
  // region by design. MINIO_REGION is a legacy alias, and MINIO_REGION_NAME does not set the
  // region at all.
  static final String REGION = "kw-west-1";

  static final String TLS_CERT_RESOURCE = "tls/test-only-silo-selfsigned.crt";
  static final String TLS_KEY_RESOURCE = "tls/test-only-silo-selfsigned.key";
  private static final String CERTS_DIR = "/certs";

  private final boolean tls;

  public SiloContainer(boolean tls) {
    super(IMAGE);
    this.tls = tls;
    withExposedPorts(S3_PORT);
    withEnv("MINIO_ROOT_USER", ACCESS_KEY);
    withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY);
    withEnv("MINIO_SITE_REGION", REGION);
    HttpWaitStrategy ready = Wait.forHttp("/minio/health/live")
      .forPort(S3_PORT)
      .forStatusCode(200);
    if (tls) {
      withCopyFileToContainer(MountableFile.forClasspathResource(TLS_CERT_RESOURCE), CERTS_DIR + "/public.crt");
      withCopyFileToContainer(MountableFile.forClasspathResource(TLS_KEY_RESOURCE), CERTS_DIR + "/private.key");
      withCommand("server", "/data", "--certs-dir", CERTS_DIR);
      ready = ready.usingTls().allowInsecure();
    } else {
      withCommand("server", "/data");
    }
    waitingFor(ready.withStartupTimeout(Duration.ofMinutes(2)));
  }

  public boolean tls() {
    return tls;
  }

  public int s3Port() {
    return getMappedPort(S3_PORT);
  }
}
