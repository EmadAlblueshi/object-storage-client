package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.S3Bucket}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.S3Bucket} original class using Vert.x codegen.
 */
public class S3BucketConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, S3Bucket obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "dataRedundancy":
          if (member.getValue() instanceof String) {
            obj.setDataRedundancy((String)member.getValue());
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

   static void toJson(S3Bucket obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(S3Bucket obj, java.util.Map<String, Object> json) {
    if (obj.getDataRedundancy() != null) {
      json.put("dataRedundancy", obj.getDataRedundancy());
    }
    if (obj.getType() != null) {
      json.put("type", obj.getType());
    }
  }
}
