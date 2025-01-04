package io.github.emadalblueshi.objectstorage.client.s3.impl;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "Error")
public class S3ErrorResponse {

  @JacksonXmlProperty(localName = "Code")
  private String code;

  @JacksonXmlProperty(localName = "Message")
  private String message;

  @JacksonXmlProperty(localName = "Resource")
  private String resource;

  @JacksonXmlProperty(localName = "RequestId")
  private String requestId;

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getResource() {
    return resource;
  }

  public void setResource(String resource) {
    this.resource = resource;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  @Override
  public String toString() {
    return "S3ErrorResponse{" +
        "code='" + code + '\'' +
        ", message='" + message + '\'' +
        // ", bucketName='" + bucketName + '\'' +
        ", resource='" + resource + '\'' +
        // ", region='" + region + '\'' +
        ", requestId='" + requestId + '\'' +
        // ", hostId='" + hostId + '\'' +
        '}';
  }
}