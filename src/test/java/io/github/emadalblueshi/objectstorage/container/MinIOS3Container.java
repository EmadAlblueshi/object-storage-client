package io.github.emadalblueshi.objectstorage.container;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class MinIOS3Container extends GenericContainer<MinIOS3Container> {
  private static final DockerImageName IMAGE_NAME = DockerImageName.parse("minio/minio");
  private static final String IMAGE_TAG = "RELEASE.2024-06-04T19-20-08Z";
  private static final int PORT_S3_API = 9000;
  private static final int PORT_WEB_UI = 9001;
  private static final String ACCESS_KEY = "minioadmin";
  private static final String SECRET_KEY = "minioadmin";

  private String accessKey;
  private String secretKey;

  public MinIOS3Container() {
    this(IMAGE_NAME.withTag(IMAGE_TAG));
  }

  public MinIOS3Container(String dockerImageName) {
    this(DockerImageName.parse(dockerImageName));
  }

  public MinIOS3Container(DockerImageName dockerImageName) {
    super(dockerImageName);
    dockerImageName.assertCompatibleWith(new DockerImageName[] { IMAGE_NAME });
  }

  public MinIOS3Container withAccessKey(String accessKey) {
    this.accessKey = accessKey;
    return this;
  }

  public MinIOS3Container withSecretKey(String secretKey) {
    this.secretKey = secretKey;
    return this;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public String getSecretKey() {
    return secretKey;
  }

  @Override
  public void configure() {
    setCommand("server /data --console-address :9001");
    addExposedPorts(new int[] { PORT_S3_API, PORT_WEB_UI });
    addEnv("MINIO_ROOT_USER", accessKey != null ? accessKey : (accessKey = ACCESS_KEY));
    addEnv("MINIO_ROOT_PASSWORD", secretKey != null ? secretKey : (secretKey = SECRET_KEY));
    setWaitStrategy(Wait.forListeningPorts(PORT_WEB_UI, PORT_S3_API)
        .withStartupTimeout(Duration.ofMinutes(2L)));
    // setWaitStrategy(Wait.forLogMessage("- The standard parity is set to 0. This
    // can lead to data loss.\n", 1)
    // .withStartupTimeout(Duration.ofMinutes(5L)));
  }
}
