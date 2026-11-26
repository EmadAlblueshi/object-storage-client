package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Credentials for signing S3 requests: an access key, a secret key and, for temporary
 * credentials, a session token.
 * <p>
 * Secrets never leave this object through {@link #toJson()} or {@link #toString()}. Both omit
 * (or mask) the secret key and the session token. {@link #S3Credentials(JsonObject)} does read
 * {@code secretKey} and {@code sessionToken}, so credentials can be configured from JSON, but
 * JSON is not a full round trip.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3Credentials {

  private static final String SECRET_KEY = "secretKey";
  private static final String SESSION_TOKEN = "sessionToken";
  private static final String MASK = "****";

  private String accessKey;
  private String secretKey;
  private String sessionToken;

  public S3Credentials() {
  }

  /**
   * @param accessKey the access key id
   * @param secretKey the secret access key
   */
  public S3Credentials(String accessKey, String secretKey) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }

  /** Copy constructor. */
  public S3Credentials(S3Credentials other) {
    this.accessKey = other.accessKey;
    this.secretKey = other.secretKey;
    this.sessionToken = other.sessionToken;
  }

  /** Reads {@code accessKey}, {@code secretKey} and {@code sessionToken}. */
  public S3Credentials(JsonObject json) {
    S3CredentialsConverter.fromJson(json, this);
    this.secretKey = json.getString(SECRET_KEY);
    this.sessionToken = json.getString(SESSION_TOKEN);
  }

  public String getAccessKey() {
    return accessKey;
  }

  public S3Credentials setAccessKey(String accessKey) {
    this.accessKey = accessKey;
    return this;
  }

  /** The secret access key. Never included in {@link #toJson()} or {@link #toString()}. */
  @GenIgnore
  public String getSecretKey() {
    return secretKey;
  }

  @GenIgnore
  public S3Credentials setSecretKey(String secretKey) {
    this.secretKey = secretKey;
    return this;
  }

  /**
   * The session token of temporary credentials, or {@code null}. Never included in
   * {@link #toJson()} or {@link #toString()}.
   */
  @GenIgnore
  public String getSessionToken() {
    return sessionToken;
  }

  @GenIgnore
  public S3Credentials setSessionToken(String sessionToken) {
    this.sessionToken = sessionToken;
    return this;
  }

  /** JSON without secrets: only {@code accessKey}. */
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3CredentialsConverter.toJson(this, json);
    return json;
  }

  /** Masks the secret key and the session token. */
  @Override
  public String toString() {
    return "S3Credentials{accessKey=" + accessKey
      + ", secretKey=" + (secretKey == null ? null : MASK)
      + ", sessionToken=" + (sessionToken == null ? null : MASK) + '}';
  }
}
