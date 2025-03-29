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
public class CreateBucketConfiguration {

  @JacksonXmlProperty(isAttribute = true)
  private final String xmlns = "http://s3.amazonaws.com/doc/2006-03-01/";

  @JacksonXmlProperty(localName = "LocationConstraint")
  private String locationConstraint;

  @JacksonXmlProperty(localName = "Location")
  private Location location;

  @JacksonXmlProperty(localName = "Bucket")
  private Bucket bucket;

  public CreateBucketConfiguration() {}

  public CreateBucketConfiguration(JsonObject json) {
    CreateBucketConfigurationConverter.fromJson(json, this);
  }

  public String getLocationConstraint() {
    return locationConstraint;
  }

  public void setLocationConstraint(String locationConstraint) {
    this.locationConstraint = locationConstraint;
  }

  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    this.location = location;
  }

  public Bucket getBucket() {
    return bucket;
  }

  public void setBucket(Bucket bucket) {
    this.bucket = bucket;
  }
}
