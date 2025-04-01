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
public class S3AccessControlPolicy {

  public S3AccessControlPolicy() {
  }

  public S3AccessControlPolicy(JsonObject json) {
    S3AccessControlPolicyConverter.fromJson(json, this);
  }

  @JacksonXmlProperty(localName = "Owner")
  private S3Owner s3Owner;

  @JacksonXmlProperty(localName = "AccessControlList")
  private S3AccessControlList s3AccessControlList;

  public S3Owner getOwner() {
    return s3Owner;
  }

  public void setOwner(S3Owner s3Owner) {
    this.s3Owner = s3Owner;
  }

  public S3AccessControlList getAccessControlList() {
    return s3AccessControlList;
  }

  public void setAccessControlList(S3AccessControlList s3AccessControlList) {
    this.s3AccessControlList = s3AccessControlList;
  }

}
