package io.github.emadalblueshi.objectstorage.client;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class S3Options {

  public static final S3SignatureVersion DEFAULT_REQUEST_SIGNATURE_VERSION = S3SignatureVersion.V4;
  public static final String DEFAULT_ACCESS_KEY = "accesskey";
  public static final String DEFAULT_SECRET_KEY = "secretkey";
  public static final String DEFAULT_REGION = "default";
  public static final String DEFAULT_SERVICE = "s3";

  private S3SignatureVersion signatureVersion = DEFAULT_REQUEST_SIGNATURE_VERSION;
  private String accessKey = DEFAULT_ACCESS_KEY;
  private String secretKey = DEFAULT_SECRET_KEY;
  private String region = DEFAULT_REGION;
  private String service = DEFAULT_SERVICE;

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
