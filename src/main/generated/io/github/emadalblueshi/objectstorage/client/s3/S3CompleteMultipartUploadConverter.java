package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.S3CompleteMultipartUpload}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.S3CompleteMultipartUpload} original class using Vert.x codegen.
 */
public class S3CompleteMultipartUploadConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, S3CompleteMultipartUpload obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "parts":
          if (member.getValue() instanceof JsonArray) {
            java.util.ArrayList<io.github.emadalblueshi.objectstorage.client.s3.S3Part> list =  new java.util.ArrayList<>();
            ((Iterable<Object>)member.getValue()).forEach( item -> {
              if (item instanceof JsonObject)
                list.add(new io.github.emadalblueshi.objectstorage.client.s3.S3Part((io.vertx.core.json.JsonObject)item));
            });
            obj.setParts(list);
          }
          break;
      }
    }
  }

   static void toJson(S3CompleteMultipartUpload obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(S3CompleteMultipartUpload obj, java.util.Map<String, Object> json) {
  }
}
