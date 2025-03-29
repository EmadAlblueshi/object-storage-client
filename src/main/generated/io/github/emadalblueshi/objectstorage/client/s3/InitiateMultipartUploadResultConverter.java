package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.InitiateMultipartUploadResult}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.InitiateMultipartUploadResult} original class using Vert.x codegen.
 */
public class InitiateMultipartUploadResultConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, InitiateMultipartUploadResult obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "bucket":
          if (member.getValue() instanceof String) {
            obj.setBucket((String)member.getValue());
          }
          break;
        case "key":
          if (member.getValue() instanceof String) {
            obj.setKey((String)member.getValue());
          }
          break;
        case "uploadId":
          if (member.getValue() instanceof String) {
            obj.setUploadId((String)member.getValue());
          }
          break;
      }
    }
  }

   static void toJson(InitiateMultipartUploadResult obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(InitiateMultipartUploadResult obj, java.util.Map<String, Object> json) {
    if (obj.getBucket() != null) {
      json.put("bucket", obj.getBucket());
    }
    if (obj.getKey() != null) {
      json.put("key", obj.getKey());
    }
    if (obj.getUploadId() != null) {
      json.put("uploadId", obj.getUploadId());
    }
  }
}
