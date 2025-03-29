package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.CompleteMultipartUploadResult}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.CompleteMultipartUploadResult} original class using Vert.x codegen.
 */
public class CompleteMultipartUploadResultConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, CompleteMultipartUploadResult obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "bucket":
          if (member.getValue() instanceof String) {
            obj.setBucket((String)member.getValue());
          }
          break;
        case "eTag":
          if (member.getValue() instanceof String) {
            obj.setETag((String)member.getValue());
          }
          break;
        case "key":
          if (member.getValue() instanceof String) {
            obj.setKey((String)member.getValue());
          }
          break;
        case "location":
          if (member.getValue() instanceof String) {
            obj.setLocation((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(CompleteMultipartUploadResult obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(CompleteMultipartUploadResult obj, java.util.Map<String, Object> json) {
    if (obj.getBucket() != null) {
      json.put("bucket", obj.getBucket());
    }
    if (obj.getETag() != null) {
      json.put("eTag", obj.getETag());
    }
    if (obj.getKey() != null) {
      json.put("key", obj.getKey());
    }
    if (obj.getLocation() != null) {
      json.put("location", obj.getLocation());
    }
  }
}
