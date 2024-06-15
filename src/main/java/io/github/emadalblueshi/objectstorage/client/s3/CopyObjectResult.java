package io.github.emadalblueshi.objectstorage.client.s3;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class CopyObjectResult {

  @JsonProperty("LastModified")
  private Instant lastModified;

  @JsonProperty("ETag")
  private String ETag;

  public Instant getLastModified() {
    return lastModified;
  }

  public void setLastModified(Instant lastModified) {
    this.lastModified = lastModified;
  }

  public String getETag() {
    return ETag;
  }

  public void setETag(String eTag) {
    ETag = eTag;
  }

}
