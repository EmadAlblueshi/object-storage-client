package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.Grant}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.Grant} original class using Vert.x codegen.
 */
public class GrantConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, Grant obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "grantee":
          if (member.getValue() instanceof JsonObject) {
            obj.setGrantee(new io.github.emadalblueshi.objectstorage.client.s3.Grantee((io.vertx.core.json.JsonObject)member.getValue()));
          }
          break;
        case "permission":
          if (member.getValue() instanceof String) {
            obj.setPermission((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(Grant obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(Grant obj, java.util.Map<String, Object> json) {
    if (obj.getPermission() != null) {
      json.put("permission", obj.getPermission());
    }
  }
}
