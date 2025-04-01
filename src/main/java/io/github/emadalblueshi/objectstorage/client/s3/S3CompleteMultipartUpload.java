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
public class S3CompleteMultipartUpload {

  @JacksonXmlProperty(isAttribute = true)
  private final String xmlns = "http://s3.amazonaws.com/doc/2006-03-01/";

  @JacksonXmlElementWrapper(useWrapping = false)
  @JacksonXmlProperty(localName = "Part")
  private List<S3Part> s3Parts;

  public S3CompleteMultipartUpload() {
  }

  public S3CompleteMultipartUpload(JsonObject json) {
    S3CompleteMultipartUploadConverter.fromJson(json, this);
  }

  public S3CompleteMultipartUpload(List<S3Part> s3Parts) {
    this.s3Parts = s3Parts;
  }

  public List<S3Part> getParts() {
    return s3Parts;
  }

  public void setParts(List<S3Part> s3Parts) {
    this.s3Parts = s3Parts;
  }
}
