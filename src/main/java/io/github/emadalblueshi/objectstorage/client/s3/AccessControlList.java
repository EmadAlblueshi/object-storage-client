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
public class AccessControlList {

  public AccessControlList() {
  }

  public AccessControlList(JsonObject json) {
    AccessControlListConverter.fromJson(json, this);
  }

  @JacksonXmlProperty(localName = "Grant")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<Grant> grantList;

  public List<Grant> getGrantList() {
    return grantList;
  }

  public void setGrantList(List<Grant> grantList) {
    this.grantList = grantList;
  }

}
