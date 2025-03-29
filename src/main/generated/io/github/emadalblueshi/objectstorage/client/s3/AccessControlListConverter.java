package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.AccessControlList}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.AccessControlList} original class using Vert.x codegen.
 */
public class AccessControlListConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, AccessControlList obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "grantList":
          if (member.getValue() instanceof JsonArray) {
            java.util.ArrayList<io.github.emadalblueshi.objectstorage.client.s3.Grant> list =  new java.util.ArrayList<>();
            ((Iterable<Object>)member.getValue()).forEach( item -> {
              if (item instanceof JsonObject)
                list.add(new io.github.emadalblueshi.objectstorage.client.s3.Grant((io.vertx.core.json.JsonObject)item));
            });
            obj.setGrantList(list);
          }
          break;
      }
    }
  }

   static void toJson(AccessControlList obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(AccessControlList obj, java.util.Map<String, Object> json) {
  }
}
