package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.S3Owner}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.S3Owner} original class using Vert.x codegen.
 */
public class S3OwnerConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, S3Owner obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "displayName":
          if (member.getValue() instanceof String) {
            obj.setDisplayName((String)member.getValue());
          }
          break;
        case "id":
          if (member.getValue() instanceof String) {
            obj.setId((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(S3Owner obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(S3Owner obj, java.util.Map<String, Object> json) {
    if (obj.getDisplayName() != null) {
      json.put("displayName", obj.getDisplayName());
    }
    if (obj.getId() != null) {
      json.put("id", obj.getId());
    }
  }
}
