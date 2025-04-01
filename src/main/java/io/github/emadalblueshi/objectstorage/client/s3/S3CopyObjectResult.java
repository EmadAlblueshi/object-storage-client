package io.github.emadalblueshi.objectstorage.client.s3;

import java.time.Instant;

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
@JacksonXmlRootElement(localName = "CopyObjectResult")
public class S3CopyObjectResult {

  public S3CopyObjectResult() {}

  public S3CopyObjectResult(JsonObject json) {
    S3CopyObjectResultConverter.fromJson(json, this);
  }

  @JacksonXmlProperty(localName = "LastModified")
  private Instant lastModified;

  @JacksonXmlProperty(localName = "ETag")
  private String ETag;

  public Instant getLastModified() {
    return lastModified;
  }

  public void setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
  }

  public String getETag() {
    return ETag;
  }

  public void setETag(String eTag) {
    ETag = eTag;
  }

}
