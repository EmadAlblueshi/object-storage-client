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
@JacksonXmlRootElement(localName = "Part")
public class S3Part {

  @JacksonXmlProperty(localName = "PartNumber")
  private int partNumber;

  @JacksonXmlProperty(localName = "ETag")
  private String ETag;

  public S3Part() {}

  public S3Part(JsonObject json) {
    S3PartConverter.fromJson(json, this);
  }

  public S3Part(int partNumber, String ETag) {
    this.partNumber = partNumber;
    this.ETag = ETag;
  }

  public int getPartNumber() {
    return partNumber;
  }

  public void setPartNumber(int partNumber) {
    this.partNumber = partNumber;
  }

  public String getETag() {
    return ETag;
  }

  public void setETag(String eTag) {
    ETag = eTag;
  }
}
