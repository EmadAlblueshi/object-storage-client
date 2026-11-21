package io.github.emadalblueshi.vertx.s3.backend;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;

import io.vertx.core.http.HttpClientOptions;

/**
 * An opt-in, already running S3-compatible service outside the build, configured only from the
 * environment:
 * <pre>
 * S3_ENDPOINT    e.g. https://s3.example.com (scheme, host and optional port)
 * S3_REGION      the region the endpoint serves
 * S3_ACCESS_KEY
 * S3_SECRET_KEY
 * S3_PATH_STYLE  optional: false for virtual-hosted-style addressing (bucket.host); default true
 * </pre>
 * Tests using it are skipped, never failed, unless the first four are set. Nothing about it is stored
 * in the repository, and the secret key is never printed. {@link #start()} and {@link #stop()} do
 * nothing: the service is not managed by the tests.
 * <p>
 * It is a real account: tests must create only uniquely named resources and delete them
 * afterwards (see {@link TestResources}), and keep bucket creation to a minimum.
 */
public final class ExternalBackend implements S3TestBackend {

  /** The environment variables that enable the external backend; all must be set. */
  public static final List<String> VARIABLES = List.of("S3_ENDPOINT", "S3_REGION", "S3_ACCESS_KEY", "S3_SECRET_KEY");

  /** The {@code disabledReason} for {@code @EnabledIf} on external test classes. */
  public static final String DISABLED_REASON =
    "External backend not configured: set S3_ENDPOINT, S3_REGION, S3_ACCESS_KEY and S3_SECRET_KEY";

  private final URI endpoint;
  private final String region;
  private final String accessKey;
  private final String secretKey;
  private final boolean pathStyleAccess;

  private ExternalBackend(URI endpoint, String region, String accessKey, String secretKey, boolean pathStyleAccess) {
    this.endpoint = endpoint;
    this.region = region;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.pathStyleAccess = pathStyleAccess;
  }

  /** Whether all {@link #VARIABLES} are set (non-blank). */
  public static boolean configured() {
    return configured(System::getenv);
  }

  static boolean configured(UnaryOperator<String> env) {
    return VARIABLES.stream().noneMatch(name -> blank(env.apply(name)));
  }

  /**
   * The backend configured by the environment. Aborts the calling test or class (JUnit reports
   * it as skipped) unless all {@link #VARIABLES} are set.
   *
   * @throws IllegalArgumentException if {@code S3_PATH_STYLE} is set to something other than
   *     {@code true} or {@code false}
   */
  public static ExternalBackend fromEnvironment() {
    return fromVariables(System::getenv);
  }

  /** {@link #fromEnvironment()} reading the variables from {@code env}. Package-private for tests. */
  static ExternalBackend fromVariables(UnaryOperator<String> env) {
    List<String> missing = VARIABLES.stream().filter(name -> blank(env.apply(name))).collect(Collectors.toList());
    Assumptions.assumeTrue(missing.isEmpty(), () -> "External backend not configured; set " + String.join(", ", missing));
    String endpoint = env.apply("S3_ENDPOINT").trim();
    while (endpoint.endsWith("/")) {
      endpoint = endpoint.substring(0, endpoint.length() - 1);
    }
    return new ExternalBackend(URI.create(endpoint), env.apply("S3_REGION").trim(),
      env.apply("S3_ACCESS_KEY").trim(), env.apply("S3_SECRET_KEY").trim(), pathStyle(env.apply("S3_PATH_STYLE")));
  }

  private static boolean pathStyle(String value) {
    if (blank(value)) {
      return true;
    }
    switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true":
        return true;
      case "false":
        return false;
      default:
        throw new IllegalArgumentException("S3_PATH_STYLE must be true or false, not \"" + value + "\"");
    }
  }

  /**
   * Whether to use path-style addressing ({@code S3_PATH_STYLE}, default {@code true}); with
   * {@code false} the suites run virtual-hosted-style ({@code bucket.host}).
   */
  public boolean pathStyleAccess() {
    return pathStyleAccess;
  }

  @Override
  public String name() {
    return "external";
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
  }

  @Override
  public URI endpoint() {
    return endpoint;
  }

  @Override
  public String region() {
    return region;
  }

  @Override
  public String accessKey() {
    return accessKey;
  }

  @Override
  public String secretKey() {
    return secretKey;
  }

  @Override
  public boolean tls() {
    return "https".equals(endpoint.getScheme().toLowerCase(Locale.ROOT));
  }

  /** Trusts what the JVM trusts (a public CA for a real service); host verification stays enabled. */
  @Override
  public HttpClientOptions httpClientOptions() {
    int port = endpoint.getPort() != -1 ? endpoint.getPort() : tls() ? 443 : 80;
    return new HttpClientOptions().setDefaultHost(endpoint.getHost()).setDefaultPort(port).setSsl(tls());
  }

  /** Never includes the secret key. */
  @Override
  public String toString() {
    return "ExternalBackend{endpoint=" + endpoint + ", region=" + region + ", pathStyleAccess=" + pathStyleAccess + '}';
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
