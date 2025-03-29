package io.github.emadalblueshi.objectstorage.client.s3;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
@JacksonXmlRootElement(localName = "CompleteMultipartUploadResult", namespace = "http://s3.amazonaws.com/doc/2006-03-01/")
public class CompleteMultipartUploadResult {

  @JacksonXmlProperty(localName = "Location")
  private String location;

  @JacksonXmlProperty(localName = "Bucket")
  private String bucket;

  @JacksonXmlProperty(localName = "Key")
  private String key;

  @JacksonXmlProperty(localName = "ETag")
  private String ETag;

  public CompleteMultipartUploadResult() {}

  public CompleteMultipartUploadResult(JsonObject json) {
    CompleteMultipartUploadResultConverter.fromJson(json, this);
  }

  public String getLocation() {
    return location;
  }

  public String getBucket() {
    return bucket;
  }

  public String getKey() {
    return key;
  }

  public String getETag() {
    return ETag;
  }

  public void setLocation(String location) {
    this.location = location;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public void setETag(String eTag) {
    ETag = eTag;
  }
}
