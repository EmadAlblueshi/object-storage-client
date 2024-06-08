package io.github.emadalblueshi.objectstorage.client;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.ObjectStorageClientOptions}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.ObjectStorageClientOptions} original class using Vert.x codegen.
 */
public class ObjectStorageClientOptionsConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, ObjectStorageClientOptions obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "s3Options":
          if (member.getValue() instanceof JsonObject) {
            obj.setS3Options(new io.github.emadalblueshi.objectstorage.client.s3.S3Options((io.vertx.core.json.JsonObject)member.getValue()));
          }
          break;
      }
    }
  }

   static void toJson(ObjectStorageClientOptions obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(ObjectStorageClientOptions obj, java.util.Map<String, Object> json) {
  }
}
