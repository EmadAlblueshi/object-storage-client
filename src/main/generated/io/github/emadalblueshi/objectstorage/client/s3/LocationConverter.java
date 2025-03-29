package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.Location}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.Location} original class using Vert.x codegen.
 */
public class LocationConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, Location obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "name":
          if (member.getValue() instanceof String) {
            obj.setName((String)member.getValue());
          }
          break;
        case "type":
          if (member.getValue() instanceof String) {
            obj.setType((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(Location obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(Location obj, java.util.Map<String, Object> json) {
    if (obj.getName() != null) {
      json.put("name", obj.getName());
    }
    if (obj.getType() != null) {
      json.put("type", obj.getType());
    }
  }
}
