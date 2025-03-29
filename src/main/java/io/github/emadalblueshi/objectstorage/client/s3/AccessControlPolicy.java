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
@JacksonXmlRootElement(localName = "AccessControlPolicy")
public class AccessControlPolicy {

  public AccessControlPolicy() {
  }

  public AccessControlPolicy(JsonObject json) {
    AccessControlPolicyConverter.fromJson(json, this);
  }

  @JacksonXmlProperty(localName = "Owner")
  private Owner owner;

  @JacksonXmlProperty(localName = "AccessControlList")
  private AccessControlList accessControlList;

  public Owner getOwner() {
    return owner;
  }

  public void setOwner(Owner owner) {
    this.owner = owner;
  }

  public AccessControlList getAccessControlList() {
    return accessControlList;
  }

  public void setAccessControlList(AccessControlList accessControlList) {
    this.accessControlList = accessControlList;
  }

}
