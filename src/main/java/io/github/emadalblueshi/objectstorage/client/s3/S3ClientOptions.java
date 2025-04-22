package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3ClientOptions {

  private static final String DEFAULT_REGION = "default";
  private static final String DEFAULT_SERVICE = "s3";

  private WebClientOptions webClientOptions;

  private String region;
  private String service;

  private S3AuthOptions authOptions;

  public S3ClientOptions() {
    setWebClientOptions(new WebClientOptions());
    setRegion(DEFAULT_REGION);
    setService(DEFAULT_SERVICE);
    setAuthOptions(new S3AuthOptions());
  }

  public S3ClientOptions(S3ClientOptions other) {
    setWebClientOptions(other.getWebClientOptions());
    setRegion(other.getRegion());
    setService(other.getService());
    setAuthOptions(other.getAuthOptions());
  }

  public S3ClientOptions(JsonObject json) {
    S3ClientOptionsConverter.fromJson(json, this);
  }

  public S3ClientOptions setWebClientOptions(WebClientOptions webClientOptions) {
    this.webClientOptions = webClientOptions;
    return this;
  }
  public WebClientOptions getWebClientOptions() {
    return webClientOptions;
  }

  public S3ClientOptions setRegion(String region) {
    this.region = region;
    return this;
  }

  public String getRegion() {
    return region;
  }

  public S3ClientOptions setService(String service) {
    this.service = service;
    return this;
  }

  public String getService() {
    return service;
  }

  public S3ClientOptions setAuthOptions(S3AuthOptions s3AuthOptions) {
    this.authOptions = s3AuthOptions;
    return this;
  }

  public S3AuthOptions getAuthOptions() {
    return authOptions;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3ClientOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3ClientOptions{" +
        "webClientOptions=" + webClientOptions +
        ", region='" + region + '\'' +
        ", service='" + service + '\'' +
        ", authOptions=" + authOptions +
        '}';
  }
}
