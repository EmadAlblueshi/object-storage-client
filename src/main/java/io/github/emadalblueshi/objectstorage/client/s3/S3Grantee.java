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
@JacksonXmlRootElement(localName = "Grantee")
public class S3Grantee {

  @JacksonXmlProperty(isAttribute = true, namespace = "xsi:type", localName = "type")
  private String attributeType;

  @JacksonXmlProperty(localName = "Type")
  private String type;

  @JacksonXmlProperty(localName = "ID")
  private String id;

  @JacksonXmlProperty(localName = "DisplayName")
  private String displayName;

  @JacksonXmlProperty(localName = "URI")
  private String uri;

  public S3Grantee() {
  }

  public S3Grantee(JsonObject json) {
    S3GranteeConverter.fromJson(json, this);
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getAttributeType() {
    return attributeType;
  }

  public void setAttributeType(String attributeType) {
    this.attributeType = attributeType;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getUri() {
    return uri;
  }

  public void setUri(String uri) {
    this.uri = uri;
  }
}
