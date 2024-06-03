package io.github.emadalblueshi.objectstorage.util;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.QueryStringEncoder;
import io.vertx.core.MultiMap;

import static io.github.emadalblueshi.objectstorage.util.Hash.*;

public class S3SignatureV4 {

  public static void sign(String host,
      String method, MultiMap headers, String path, MultiMap queryParams,
      byte[] body,
      String accessKey, String secretKey, String region, String service) {

    QueryStringEncoder queryStringEncoder = new QueryStringEncoder("");
    queryParams.entries().forEach(e -> queryStringEncoder.addParam(e.getKey(), e.getValue()));
    String query = queryStringEncoder.toString().replace("?", "");

    String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
    String contentSha256 = hex(sha256(body));
    String isoDate = date.substring(0, 8);

    headers.set("host", host);
    headers.set("x-amz-content-sha256", contentSha256);
    headers.set("x-amz-date", date);

    List<String> canonicalRequestLines = new ArrayList<>();
    canonicalRequestLines.add(method);
    canonicalRequestLines.add(path);
    canonicalRequestLines.add(query);
    List<String> hashedHeaders = new ArrayList<>();

    List<String> headerSortedKeys = headers
        .entries()
        .stream()
        .map(Map.Entry::getKey)
        .map(String::toLowerCase)
        .sorted(Comparator.naturalOrder())
        .collect(Collectors.toList());

    for (String key : headerSortedKeys) {
      hashedHeaders.add(key);
      canonicalRequestLines.add(key + ":" + headers.get(key).trim());
    }

    String signedHeaders = hashedHeaders.stream().collect(Collectors.joining(";"));

    canonicalRequestLines.add(null); // after headers
    canonicalRequestLines.add(signedHeaders);
    canonicalRequestLines.add(contentSha256);

    String canonicalRequestBody = canonicalRequestLines
        .stream()
        .map(line -> line == null ? "" : line)
        .collect(Collectors.joining("\n"));

    String canonicalRequestHash = hex(sha256(canonicalRequestBody.getBytes(StandardCharsets.UTF_8)));

    List<String> stringToSignLines = new ArrayList<>();
    stringToSignLines.add("AWS4-HMAC-SHA256");
    stringToSignLines.add(date);
    String credentialScope = isoDate + "/" + region + "/" + service + "/aws4_request";
    stringToSignLines.add(credentialScope);
    stringToSignLines.add(canonicalRequestHash);

    String stringToSign = stringToSignLines.stream().collect(Collectors.joining("\n"));

    byte[] dateKey = hmacSHA256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), isoDate);
    byte[] dateRegionkey = hmacSHA256(dateKey, region);
    byte[] dateRegionServiceKey = hmacSHA256(dateRegionkey, service);
    byte[] signingKey = hmacSHA256(dateRegionServiceKey, "aws4_request");
    String signature = hex(hmacSHA256(signingKey, stringToSign));

    String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
        + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature;

    headers.set("authorization", authorization);

  }

}
