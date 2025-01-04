package io.github.emadalblueshi.objectstorage.client.s3;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@JacksonXmlRootElement(localName = "PolicyStatus")
public class PolicyStatus {

  @JacksonXmlProperty(localName = "IsPublic")
  private boolean isPublic;

  public PolicyStatus() {
  }

  public boolean isPublic() {
    return isPublic;
  }

  public void setPublic(boolean isPublic) {
    this.isPublic = isPublic;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (isPublic ? 1231 : 1237);
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    PolicyStatus other = (PolicyStatus) obj;
    if (isPublic != other.isPublic)
      return false;
    return true;
  }

  @Override
  public String toString() {
    return "PolicyStatus [isPublic=" + isPublic + "]";
  }

}
