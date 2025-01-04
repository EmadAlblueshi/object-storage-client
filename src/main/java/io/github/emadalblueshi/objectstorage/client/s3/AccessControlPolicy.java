package io.github.emadalblueshi.objectstorage.client.s3;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@JacksonXmlRootElement(localName = "AccessControlPolicy")
public class AccessControlPolicy {

  public AccessControlPolicy() {
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
