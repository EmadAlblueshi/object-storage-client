package io.github.emadalblueshi.vertx.s3;

import java.util.Objects;
import java.util.Set;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Options for an {@link S3Client}.
 * <p>
 * The {@linkplain #setEndpoint endpoint} URI is the single source of scheme, host and port.
 * {@link HttpClientOptions} and {@link PoolOptions} are <em>composed</em>, not inherited, and
 * carry transport settings only (TLS trust, timeouts, keep-alive, pool sizes). Their default
 * host, default port and {@code ssl} flag are derived from the endpoint, so a conflicting value
 * there is rejected when the client is created.
 * <p>
 * Credentials are static, set with {@link #setCredentials(S3Credentials)}, and can come from JSON
 * like every other option, e.g. {@code new S3ClientOptions(config.getJsonObject("s3"))}. Secrets
 * are read from JSON but never written by {@link #toJson()}. The client copies them when it is
 * created; to use other keys, create a new client. Required values (endpoint, region,
 * credentials) are validated by {@code S3Client.create}, which throws
 * {@link IllegalArgumentException} for invalid options.
 * <p>
 * The same options configure the {@link S3Presigner} ({@link S3Client#presigner()} or
 * {@link S3Presigner#create(S3ClientOptions)}): it uses the endpoint, or the
 * {@linkplain #setPublicEndpoint public endpoint} when set, the region, the addressing style and
 * the credentials, and ignores the transport settings.
 * <p>
 * Payload signing is not configurable: the endpoint scheme decides. Over HTTPS, payloads are sent
 * as {@code UNSIGNED-PAYLOAD} (TLS protects them); over plain HTTP, they are always signed.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3ClientOptions {

  /**
   * Default: path-style addressing ({@code https://host/bucket/key}), which every S3-compatible
   * server supports (MinIO-compatible servers need it unless a domain is configured).
   * <strong>This differs from the AWS SDK default</strong>, which is virtual-hosted-style. Set
   * {@code false} for virtual-hosted-style, e.g. on AWS.
   */
  public static final boolean DEFAULT_PATH_STYLE_ACCESS = true;

  /**
   * Default: {@code getObject} buffers objects of up to 4 MiB. It is for small objects only; use
   * {@code getObjectStream} for anything larger.
   */
  public static final int DEFAULT_MAX_BUFFERED_OBJECT_SIZE = 4 * 1024 * 1024;

  // Keys Vert.x writes in clear inside HttpClientOptions JSON (key/trust store passwords, proxy auth).
  private static final Set<String> SECRET_JSON_KEYS = Set.of("password", "aliasPassword");

  private String endpoint;
  private String publicEndpoint;
  private String region;
  private boolean pathStyleAccess;
  private S3Credentials credentials;
  private int maxBufferedObjectSize;
  private HttpClientOptions httpClientOptions;
  private PoolOptions poolOptions;

  public S3ClientOptions() {
    pathStyleAccess = DEFAULT_PATH_STYLE_ACCESS;
    maxBufferedObjectSize = DEFAULT_MAX_BUFFERED_OBJECT_SIZE;
    httpClientOptions = new HttpClientOptions();
    poolOptions = new PoolOptions();
  }

  /** Deep copy: nested options and credentials are copied. */
  public S3ClientOptions(S3ClientOptions other) {
    endpoint = other.endpoint;
    publicEndpoint = other.publicEndpoint;
    region = other.region;
    pathStyleAccess = other.pathStyleAccess;
    credentials = other.credentials == null ? null : new S3Credentials(other.credentials);
    maxBufferedObjectSize = other.maxBufferedObjectSize;
    httpClientOptions = new HttpClientOptions(other.httpClientOptions);
    poolOptions = new PoolOptions(other.poolOptions);
  }

  public S3ClientOptions(JsonObject json) {
    this();
    S3ClientOptionsConverter.fromJson(json, this);
  }

  /** The endpoint URI, e.g. {@code https://nyc3.digitaloceanspaces.com} or {@code http://localhost:9000}. */
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * Sets the endpoint URI ({@code http} or {@code https}, host and optional port; no path, query
   * or user info). It is the single source of scheme, host and port.
   */
  public S3ClientOptions setEndpoint(String endpoint) {
    this.endpoint = endpoint;
    return this;
  }

  /**
   * The endpoint that presigned URLs point to, or {@code null} (the default) to use the
   * {@linkplain #getEndpoint() endpoint}.
   */
  public String getPublicEndpoint() {
    return publicEndpoint;
  }

  /**
   * Sets the endpoint that presigned URLs point to, when it differs from the endpoint the client
   * talks to: e.g. the client uses an in-cluster address, while browsers and mobile apps need the
   * public host. Same form and rules as {@link #setEndpoint}; the region, addressing style and
   * credentials are shared. Only presigning uses it.
   */
  public S3ClientOptions setPublicEndpoint(String publicEndpoint) {
    this.publicEndpoint = publicEndpoint;
    return this;
  }

  /** The signing region, e.g. {@code us-east-1} or {@code nyc3}. Required; there is no default. */
  public String getRegion() {
    return region;
  }

  public S3ClientOptions setRegion(String region) {
    this.region = region;
    return this;
  }

  /**
   * Whether buckets are addressed path-style ({@code https://host/bucket/key}) or
   * virtual-hosted-style ({@code https://bucket.host/key}). Defaults to {@code true}
   * (path-style), unlike the AWS SDK; see {@link #DEFAULT_PATH_STYLE_ACCESS}.
   */
  public boolean isPathStyleAccess() {
    return pathStyleAccess;
  }

  public S3ClientOptions setPathStyleAccess(boolean pathStyleAccess) {
    this.pathStyleAccess = pathStyleAccess;
    return this;
  }

  /** The credentials. Required; there are no default credentials. */
  public S3Credentials getCredentials() {
    return credentials;
  }

  /**
   * Sets the credentials (an access key and a secret key, plus a session token for temporary
   * credentials). They are copied when the client is created: later changes have no effect.
   */
  public S3ClientOptions setCredentials(S3Credentials credentials) {
    this.credentials = credentials;
    return this;
  }

  /**
   * The largest object {@code getObject} buffers in memory, in bytes. A larger object fails
   * with an {@link S3ClientException} pointing to {@code getObjectStream}, without its body being
   * read.
   */
  public int getMaxBufferedObjectSize() {
    return maxBufferedObjectSize;
  }

  public S3ClientOptions setMaxBufferedObjectSize(int maxBufferedObjectSize) {
    if (maxBufferedObjectSize <= 0) {
      throw new IllegalArgumentException("maxBufferedObjectSize must be > 0");
    }
    this.maxBufferedObjectSize = maxBufferedObjectSize;
    return this;
  }

  /**
   * Transport settings for the underlying HTTP client (TLS trust, timeouts, keep-alive, ...).
   * Default host, default port and {@code ssl} must be left at their defaults: they are
   * derived from the endpoint.
   */
  public HttpClientOptions getHttpClientOptions() {
    return httpClientOptions;
  }

  public S3ClientOptions setHttpClientOptions(HttpClientOptions httpClientOptions) {
    this.httpClientOptions = Objects.requireNonNull(httpClientOptions, "httpClientOptions");
    return this;
  }

  /** Connection pool settings for the underlying HTTP client. */
  public PoolOptions getPoolOptions() {
    return poolOptions;
  }

  public S3ClientOptions setPoolOptions(PoolOptions poolOptions) {
    this.poolOptions = Objects.requireNonNull(poolOptions, "poolOptions");
    return this;
  }

  /**
   * JSON form, without secrets, for logging and inspection: it can't be used to copy options with
   * their credentials. Credentials appear without the secret key and session token (see
   * {@link S3Credentials#toJson()}). In the nested HTTP client options, every
   * {@code password}/{@code aliasPassword} entry (key and trust store passwords, proxy
   * credentials) is removed: Vert.x itself writes those in clear.
   */
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ClientOptionsConverter.toJson(this, json);
    JsonObject http = json.getJsonObject("httpClientOptions");
    if (http != null) {
      removeSecrets(http);
    }
    return json;
  }

  private static void removeSecrets(Object node) {
    if (node instanceof JsonObject) {
      JsonObject object = (JsonObject) node;
      SECRET_JSON_KEYS.forEach(object::remove);
      object.forEach(entry -> removeSecrets(entry.getValue()));
    } else if (node instanceof JsonArray) {
      ((JsonArray) node).forEach(S3ClientOptions::removeSecrets);
    }
  }

  /** Never contains secrets. Nested HTTP and pool options are not printed. */
  @Override
  public String toString() {
    return "S3ClientOptions{endpoint=" + endpoint
      + ", publicEndpoint=" + publicEndpoint
      + ", region=" + region
      + ", pathStyleAccess=" + pathStyleAccess
      + ", credentials=" + credentials
      + ", maxBufferedObjectSize=" + maxBufferedObjectSize + '}';
  }
}
