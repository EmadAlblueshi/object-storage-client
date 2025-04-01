package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3AuthOptions {

  private static final S3SignatureVersion DEFAULT_SIGNATURE_VERSION = S3SignatureVersion.V4;
  private static final String DEFAULT_ACCESS_KEY = "accesskey";
  private static final String DEFAULT_SECRET_KEY = "secretkey";

  private S3SignatureVersion s3SignatureVersion;
  private String accessKey;
  private String secretKey;

  public S3AuthOptions() {
    setSignatureVersion(DEFAULT_SIGNATURE_VERSION);
    setAccessKey(DEFAULT_ACCESS_KEY);
    setSecretKey(DEFAULT_SECRET_KEY);
  }

  public S3AuthOptions(S3AuthOptions other) {
    setSignatureVersion(other.getSignatureVersion());
    setAccessKey(other.getAccessKey());
    setSecretKey(other.getSecretKey());
  }

  public S3AuthOptions(JsonObject json) {
    S3AuthOptionsConverter.fromJson(json, this);
  }

  public S3AuthOptions setAccessKey(String accessKey) {
    this.accessKey = accessKey;
    return this;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public S3AuthOptions setSecretKey(String secretKey) {
    this.secretKey = secretKey;
    return this;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public S3AuthOptions setSignatureVersion(S3SignatureVersion s3SignatureVersion) {
    this.s3SignatureVersion = s3SignatureVersion;
    return this;
  }

  public S3SignatureVersion getSignatureVersion() {
    return s3SignatureVersion;
  }
}
