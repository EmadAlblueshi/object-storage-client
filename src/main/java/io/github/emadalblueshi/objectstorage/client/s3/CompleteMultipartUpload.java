package io.github.emadalblueshi.objectstorage.client.s3;

import java.util.List;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
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
@JacksonXmlRootElement(localName = "CompleteMultipartUpload")
public class CompleteMultipartUpload {

  @JacksonXmlProperty(isAttribute = true)
  private final String xmlns = "http://s3.amazonaws.com/doc/2006-03-01/";

  @JacksonXmlElementWrapper(useWrapping = false)
  @JacksonXmlProperty(localName = "Part")
  private List<Part> parts;

  public CompleteMultipartUpload() {
  }

  public CompleteMultipartUpload(JsonObject json) {
    CompleteMultipartUploadConverter.fromJson(json, this);
  }

  public CompleteMultipartUpload(List<Part> parts) {
    this.parts = parts;
  }

  public List<Part> getParts() {
    return parts;
  }

  public void setParts(List<Part> parts) {
    this.parts = parts;
  }
}
