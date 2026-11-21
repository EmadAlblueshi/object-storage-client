package io.github.emadalblueshi.vertx.s3;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * The owner of a bucket or object: the canonical user id and, where the provider reports one,
 * a display name.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3Owner {

  private String id;
  private String displayName;

  public S3Owner() {
  }

  public S3Owner(S3Owner other) {
    this.id = other.id;
    this.displayName = other.displayName;
  }

  public S3Owner(JsonObject json) {
    S3OwnerConverter.fromJson(json, this);
  }

  /** The canonical user id. */
  public String getId() {
    return id;
  }

  public S3Owner setId(String id) {
    this.id = id;
    return this;
  }

  /** The display name, or {@code null} (not every provider returns one). */
  public String getDisplayName() {
    return displayName;
  }

  public S3Owner setDisplayName(String displayName) {
    this.displayName = displayName;
    return this;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3OwnerConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3Owner{id=" + id + ", displayName=" + displayName + '}';
  }
}
