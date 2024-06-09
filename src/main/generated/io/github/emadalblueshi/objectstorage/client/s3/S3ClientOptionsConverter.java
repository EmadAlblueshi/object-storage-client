package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.S3ClientOptions}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.S3ClientOptions} original class using Vert.x codegen.
 */
public class S3ClientOptionsConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, S3ClientOptions obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "authOptions":
          if (member.getValue() instanceof JsonObject) {
            obj.setAuthOptions(new io.github.emadalblueshi.objectstorage.client.s3.S3AuthOptions((io.vertx.core.json.JsonObject)member.getValue()));
          }
          break;
        case "region":
          if (member.getValue() instanceof String) {
            obj.setRegion((String)member.getValue());
          }
          break;
        case "service":
          if (member.getValue() instanceof String) {
            obj.setService((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(S3ClientOptions obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(S3ClientOptions obj, java.util.Map<String, Object> json) {
    if (obj.getRegion() != null) {
      json.put("region", obj.getRegion());
    }
    if (obj.getService() != null) {
      json.put("service", obj.getService());
    }
  }
}
