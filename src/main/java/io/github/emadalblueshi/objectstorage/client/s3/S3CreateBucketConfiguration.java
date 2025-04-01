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
@JacksonXmlRootElement(localName = "CreateBucketConfiguration")
public class S3CreateBucketConfiguration {

  @JacksonXmlProperty(isAttribute = true)
  private final String xmlns = "http://s3.amazonaws.com/doc/2006-03-01/";

  @JacksonXmlProperty(localName = "LocationConstraint")
  private String locationConstraint;

  @JacksonXmlProperty(localName = "Location")
  private S3Location s3Location;

  @JacksonXmlProperty(localName = "Bucket")
  private S3Bucket s3Bucket;

  public S3CreateBucketConfiguration() {}

  public S3CreateBucketConfiguration(JsonObject json) {
    S3CreateBucketConfigurationConverter.fromJson(json, this);
  }

  public String getLocationConstraint() {
    return locationConstraint;
  }

  public void setLocationConstraint(String locationConstraint) {
    this.locationConstraint = locationConstraint;
  }

  public S3Location getLocation() {
    return s3Location;
  }

  public void setLocation(S3Location s3Location) {
    this.s3Location = s3Location;
  }

  public S3Bucket getBucket() {
    return s3Bucket;
  }

  public void setBucket(S3Bucket s3Bucket) {
    this.s3Bucket = s3Bucket;
  }
}
