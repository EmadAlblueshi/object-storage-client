package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.CreateBucketConfiguration}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.CreateBucketConfiguration} original class using Vert.x codegen.
 */
public class CreateBucketConfigurationConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, CreateBucketConfiguration obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "bucket":
          if (member.getValue() instanceof JsonObject) {
            obj.setBucket(new io.github.emadalblueshi.objectstorage.client.s3.Bucket((io.vertx.core.json.JsonObject)member.getValue()));
          }
          break;
        case "location":
          if (member.getValue() instanceof JsonObject) {
            obj.setLocation(new io.github.emadalblueshi.objectstorage.client.s3.Location((io.vertx.core.json.JsonObject)member.getValue()));
          }
          break;
        case "locationConstraint":
          if (member.getValue() instanceof String) {
            obj.setLocationConstraint((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(CreateBucketConfiguration obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(CreateBucketConfiguration obj, java.util.Map<String, Object> json) {
    if (obj.getLocationConstraint() != null) {
      json.put("locationConstraint", obj.getLocationConstraint());
    }
  }
}
