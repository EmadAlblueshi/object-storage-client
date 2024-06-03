package io.github.emadalblueshi.objectstorage.container;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The test container is published in docker hub and its faster for testing.
 * <p>
 * see <a href="https://hub.docker.com/r/emadalblueshi/lite-ceph-s3-gw">Docker Hub</a> and
 * <a href="https://github.com/EmadAlblueshi/lite-ceph-s3-gw">Github</a>
 * <p>
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class CephRadosGatwayContainer extends GenericContainer<CephRadosGatwayContainer> {

  private static final DockerImageName IMAGE_NAME = DockerImageName.parse("emadalblueshi/lite-ceph-s3-gw");
  private static final String IMAGE_TAG = "latest";
  private static final int PORT_HTTPS = 7443;
  private static final int PORT_HTTP = 7480;
  private static final String ACCESS_KEY = "demo-key";
  private static final String SECRET_KEY = "demo-secret";
  private static final String BUCKET_NAME = "demo-bucket";

  private String accessKey;
  private String secretKey;
  private String bucketName;

  public CephRadosGatwayContainer() {
    this(IMAGE_NAME.withTag(IMAGE_TAG));
  }

  public CephRadosGatwayContainer(String dockerImageName) {
    this(DockerImageName.parse(dockerImageName));
  }

  public CephRadosGatwayContainer(DockerImageName dockerImageName) {
    super(dockerImageName);
    dockerImageName.assertCompatibleWith(new DockerImageName[] { IMAGE_NAME });
  }

  public CephRadosGatwayContainer withAccessKey(String accessKey) {
    this.accessKey = accessKey;
    return this;
  }

  public CephRadosGatwayContainer withSecretKey(String secretKey) {
    this.secretKey = secretKey;
    return this;
  }

  public CephRadosGatwayContainer withBucketName(String bucketName) {
    this.bucketName = bucketName;
    return this;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public String getBucketName() {
    return bucketName;
  }

  public void configure() {
    addExposedPorts(new int[] { PORT_HTTP, PORT_HTTPS });
    addEnv("ACCESS_KEY", accessKey != null ? accessKey : (accessKey = ACCESS_KEY));
    addEnv("SECRET_KEY", secretKey != null ? secretKey : (secretKey = SECRET_KEY));
    addEnv("BUCKET_NAME", bucketName != null ? bucketName : (bucketName = BUCKET_NAME));
    setWaitStrategy(Wait.forLogMessage("Successfully started\n", 1)
        .withStartupTimeout(Duration.ofMinutes(2L)));

  }
}
