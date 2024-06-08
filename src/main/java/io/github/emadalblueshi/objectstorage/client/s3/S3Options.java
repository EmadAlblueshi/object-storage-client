package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3Options {

  public static final S3SignatureVersion DEFAULT_REQUEST_SIGNATURE_VERSION = S3SignatureVersion.V4;
  public static final String DEFAULT_ACCESS_KEY = "accesskey";
  public static final String DEFAULT_SECRET_KEY = "secretkey";
  public static final String DEFAULT_REGION = "default";
  public static final String DEFAULT_SERVICE = "s3";

  private S3SignatureVersion signatureVersion;
  private String accessKey;
  private String secretKey;
  private String region;
  private String service;

  public S3Options() {
    setSignatureVersion(DEFAULT_REQUEST_SIGNATURE_VERSION);
    setAccessKey(DEFAULT_ACCESS_KEY);
    setSecretKey(DEFAULT_SECRET_KEY);
    setRegion(DEFAULT_REGION);
    setService(DEFAULT_SERVICE);
  }

  public S3Options(S3Options other) {
    setSignatureVersion(other.getSignatureVersion());
    setAccessKey(other.getAccessKey());
    setSecretKey(other.getSecretKey());
    setRegion(other.getRegion());
    setService(other.getService());
  }

  public S3Options(JsonObject json) {
    S3OptionsConverter.fromJson(json, this);
  }

  public S3Options setAccessKey(String accessKey) {
    this.accessKey = accessKey;
    return this;
  }

  public String getAccessKey() {
    return accessKey;
  }

  public S3Options setSecretKey(String secretKey) {
    this.secretKey = secretKey;
    return this;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public S3Options setSignatureVersion(S3SignatureVersion signatureVersion) {
    this.signatureVersion = signatureVersion;
    return this;
  }

  public S3SignatureVersion getSignatureVersion() {
    return signatureVersion;
  }

  public S3Options setRegion(String region) {
    this.region = region;
    return this;
  }

  public String getRegion() {
    return region;
  }

  public S3Options setService(String service) {
    this.service = service;
    return this;
  }

  public String getService() {
    return service;
  }
}
