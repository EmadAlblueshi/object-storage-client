package io.github.emadalblueshi.objectstorage.client.s3;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link io.github.emadalblueshi.objectstorage.client.s3.S3AuthOptions}.
 * NOTE: This class has been automatically generated from the {@link io.github.emadalblueshi.objectstorage.client.s3.S3AuthOptions} original class using Vert.x codegen.
 */
public class S3AuthOptionsConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

   static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, S3AuthOptions obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "accessKey":
          if (member.getValue() instanceof String) {
            obj.setAccessKey((String)member.getValue());
          }
          break;
        case "secretKey":
          if (member.getValue() instanceof String) {
            obj.setSecretKey((String)member.getValue());
          }
          break;
        case "signatureVersion":
          if (member.getValue() instanceof String) {
            obj.setSignatureVersion(io.github.emadalblueshi.objectstorage.client.s3.S3SignatureVersion.valueOf((String)member.getValue()));
          }
          break;
      }
    }
  }

   static void toJson(S3AuthOptions obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

   static void toJson(S3AuthOptions obj, java.util.Map<String, Object> json) {
    if (obj.getAccessKey() != null) {
      json.put("accessKey", obj.getAccessKey());
    }
    if (obj.getSecretKey() != null) {
      json.put("secretKey", obj.getSecretKey());
    }
    if (obj.getSignatureVersion() != null) {
      json.put("signatureVersion", obj.getSignatureVersion().name());
    }
  }
}
