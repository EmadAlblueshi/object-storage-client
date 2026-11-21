package io.github.emadalblueshi.vertx.s3.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;

/**
 * Validated, immutable configuration derived from {@link S3ClientOptions} by
 * {@code S3Client.create}. Every check that can fail fast happens here and throws
 * {@link IllegalArgumentException}, per the owner's decision (Phase 2 answers).
 */
final class ClientConfig {

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  final boolean tls;
  final String host;
  /** The port to connect to (80/443 when the endpoint has none). */
  final int port;
  /** Whether {@link #port} is the scheme default (then it's omitted from the Host header). */
  final boolean defaultPort;
  final String region;
  final boolean pathStyleAccess;
  /** A copy of the options' credentials, validated. */
  final S3Credentials credentials;
  final int maxBufferedObjectSize;
  final HttpClientOptions httpClientOptions;
  final PoolOptions poolOptions;
  final Clock clock;

  private ClientConfig(boolean tls, String host, int port, boolean defaultPort, S3ClientOptions options,
                       S3Credentials credentials) {
    this.tls = tls;
    this.host = host;
    this.port = port;
    this.defaultPort = defaultPort;
    this.region = options.getRegion().trim();
    this.pathStyleAccess = options.isPathStyleAccess();
    this.credentials = credentials;
    this.maxBufferedObjectSize = options.getMaxBufferedObjectSize();
    this.httpClientOptions = new HttpClientOptions(options.getHttpClientOptions())
      .setDefaultHost(host).setDefaultPort(port).setSsl(tls);
    this.poolOptions = new PoolOptions(options.getPoolOptions());
    this.clock = Clock.systemUTC();
  }

  /**
   * @throws IllegalArgumentException if the options are invalid
   * @throws NullPointerException if {@code options} is {@code null}
   */
  static ClientConfig from(S3ClientOptions options) {
    Objects.requireNonNull(options, "options");

    URI endpoint = parseEndpoint(options.getEndpoint());
    boolean tls = "https".equals(endpoint.getScheme().toLowerCase(Locale.ROOT));
    String host = endpoint.getHost();
    int port = endpoint.getPort() == -1 ? (tls ? 443 : 80) : endpoint.getPort();
    boolean defaultPort = port == (tls ? 443 : 80);

    if (options.getRegion() == null || options.getRegion().trim().isEmpty()) {
      throw new IllegalArgumentException("region is required (e.g. us-east-1, nyc3)");
    }

    S3Credentials credentials = credentials(options);

    HttpClientOptions http = Objects.requireNonNull(options.getHttpClientOptions(), "httpClientOptions");
    if (!HttpClientOptions.DEFAULT_DEFAULT_HOST.equals(http.getDefaultHost())
      || http.getDefaultPort() != HttpClientOptions.DEFAULT_DEFAULT_PORT
      || http.isSsl() != HttpClientOptions.DEFAULT_SSL) {
      throw new IllegalArgumentException("httpClientOptions must not set defaultHost, defaultPort or ssl: scheme, "
        + "host and port come from the endpoint");
    }

    if (!options.isPathStyleAccess() && isIpOrLocalhost(host)) {
      throw new IllegalArgumentException("virtual-hosted-style access needs a DNS endpoint, not " + host
        + "; use pathStyleAccess = true");
    }

    return new ClientConfig(tls, host, port, defaultPort, options, credentials);
  }

  private static URI parseEndpoint(String endpoint) {
    if (endpoint == null || endpoint.trim().isEmpty()) {
      throw new IllegalArgumentException("endpoint is required (e.g. https://nyc3.digitaloceanspaces.com)");
    }
    URI uri;
    try {
      uri = new URI(endpoint.trim());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("endpoint is not a valid URI: " + endpoint, e);
    }
    String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("endpoint must use http or https: " + endpoint);
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      throw new IllegalArgumentException("endpoint has no valid host: " + endpoint);
    }
    if (uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException("endpoint must not contain user info: " + endpoint);
    }
    String path = uri.getRawPath();
    if ((path != null && !path.isEmpty() && !"/".equals(path)) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain a path, query or fragment: " + endpoint);
    }
    return uri;
  }

  private static S3Credentials credentials(S3ClientOptions options) {
    S3Credentials credentials = options.getCredentials();
    if (credentials == null) {
      throw new IllegalArgumentException("credentials are required");
    }
    if (isBlank(credentials.getAccessKey()) || isBlank(credentials.getSecretKey())) {
      throw new IllegalArgumentException("credentials need an accessKey and a secretKey");
    }
    return new S3Credentials(credentials);
  }

  private static boolean isIpOrLocalhost(String host) {
    return host.indexOf(':') >= 0 || host.startsWith("[") || IPV4.matcher(host).matches()
      || "localhost".equalsIgnoreCase(host);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /** The host to connect to and sign for: {@code bucket.host} for virtual-hosted-style access. */
  String hostFor(String bucket) {
    return bucket == null || pathStyleAccess ? host : bucket + "." + host;
  }

  /** The request path (decoded): {@code /bucket/key} path-style, {@code /key} virtual-hosted-style. */
  String pathFor(String bucket, String key) {
    StringBuilder path = new StringBuilder("/");
    if (bucket != null && pathStyleAccess) {
      path.append(bucket);
      if (key != null) {
        path.append('/').append(key);
      }
    } else if (key != null) {
      path.append(key);
    }
    return path.toString();
  }
}
