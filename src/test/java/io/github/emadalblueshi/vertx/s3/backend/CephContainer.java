package io.github.emadalblueshi.vertx.s3.backend;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Testcontainers wrapper for Ceph RGW, the S3 gateway DigitalOcean Spaces is built on: the
 * official {@code quay.io/ceph/ceph} image, started by the test-only bootstrap
 * {@code ceph/rados-rgw.sh} as a single-node RADOS cluster (one monitor, one in-memory memstore
 * OSD) with one radosgw serving HTTP and HTTPS at once. Ready in about 15 s once the image is
 * present; the first start after a pull can take a few minutes.
 * <p>
 * The image is pinned to an exact release (never {@code latest}); don't change the tag without
 * the owner's approval.
 */
public class CephContainer extends GenericContainer<CephContainer> {

  public static final DockerImageName IMAGE = DockerImageName.parse("quay.io/ceph/ceph").withTag("v20.2.5");

  static final int HTTP_PORT = 7480;
  static final int HTTPS_PORT = 7443;

  // Test-only credentials for a throwaway container.
  static final String ACCESS_KEY = "ceph-test-access-key";
  static final String SECRET_KEY = "ceph-test-secret-key";

  // The zonegroup name: RGW accepts it as the LocationConstraint. RGW doesn't check the region of
  // a request's signature.
  static final String REGION = "test-west-1";

  public CephContainer() {
    super(IMAGE);
    withExposedPorts(HTTP_PORT, HTTPS_PORT);
    withEnv("ACCESS_KEY", ACCESS_KEY);
    withEnv("SECRET_KEY", SECRET_KEY);
    withEnv("REGION", REGION);
    withCopyFileToContainer(MountableFile.forClasspathResource("ceph/rados-rgw.sh", 0755), "/rados-rgw.sh");
    withCopyFileToContainer(MountableFile.forClasspathResource(SiloContainer.TLS_CERT_RESOURCE), "/etc/ceph/tls.crt");
    withCopyFileToContainer(MountableFile.forClasspathResource(SiloContainer.TLS_KEY_RESOURCE), "/etc/ceph/tls.key");
    withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/rados-rgw.sh"));
    waitingFor(Wait.forLogMessage(".*RGW READY.*", 1).withStartupTimeout(Duration.ofMinutes(5)));
  }

  /** The mapped port for HTTPS or plain HTTP. */
  public int s3Port(boolean tls) {
    return getMappedPort(tls ? HTTPS_PORT : HTTP_PORT);
  }
}
