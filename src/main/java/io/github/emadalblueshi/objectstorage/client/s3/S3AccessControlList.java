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
@JacksonXmlRootElement(localName = "AccessControlList")
public class S3AccessControlList {

  public S3AccessControlList() {
  }

  public S3AccessControlList(JsonObject json) {
    S3AccessControlListConverter.fromJson(json, this);
  }

  @JacksonXmlProperty(localName = "Grant")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<S3Grant> s3GrantList;

  public List<S3Grant> getGrantList() {
    return s3GrantList;
  }

  public void setGrantList(List<S3Grant> s3GrantList) {
    this.s3GrantList = s3GrantList;
  }

}
