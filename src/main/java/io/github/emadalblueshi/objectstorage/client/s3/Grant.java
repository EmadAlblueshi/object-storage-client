package io.github.emadalblueshi.objectstorage.client.s3;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@JacksonXmlRootElement(localName = "Grant")
public class Grant {

  public Grant() {
  }

  @JacksonXmlProperty(localName = "Grantee")
  private Grantee grantee;

  @JacksonXmlProperty(localName = "Permission")
  private String permission;

  public String getPermission() {
    return permission;
  }

  public void setPermission(String permission) {
    this.permission = permission;
  }

  public Grantee getGrantee() {
    return grantee;
  }

  public void setGrantee(Grantee grantee) {
    this.grantee = grantee;
  }

}
