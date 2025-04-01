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
@JacksonXmlRootElement(localName = "Bucket")
public class S3Bucket {

  @JacksonXmlProperty(localName = "DataRedundancy")
  private String dataRedundancy;

  @JacksonXmlProperty(localName = "Type")
  private String type;

  public S3Bucket() {}

  public S3Bucket(JsonObject json) {
    S3BucketConverter.fromJson(json, this);
  }

  public String getDataRedundancy() {
    return dataRedundancy;
  }

  public void setDataRedundancy(String dataRedundancy) {
    this.dataRedundancy = dataRedundancy;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }
}
