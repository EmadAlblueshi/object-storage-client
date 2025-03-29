package io.github.emadalblueshi.objectstorage.client.s3.impl;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.netty.handler.codec.http.QueryStringEncoder;
import io.vertx.core.MultiMap;

import static io.github.emadalblueshi.objectstorage.client.s3.impl.Hash.*;
import static io.vertx.core.http.HttpHeaders.*;

public class S3SignatureV4 {

  private static final String AWS4 = "AWS4";
  private static final String AWS4_HMAC_SHA256 = "AWS4-HMAC-SHA256";
  private static final String AWS4_REQUEST = "aws4_request";
  private static final String X_AMZ_CONTENT_SHA256 = "x-amz-content-sha256";
  private static final String X_AMZ_DATE = "x-amz-date";
  private static final String DATE_TIME_FORMAT = "yyyyMMdd'T'HHmmss'Z'";

  public static void sign(String host,
      String method, MultiMap headers, String path, MultiMap queryParams,
      byte[] body,
      String accessKey, String secretKey, String region, String service) {

    QueryStringEncoder queryStringEncoder = new QueryStringEncoder("");
    queryParams.entries().forEach(e -> queryStringEncoder.addParam(e.getKey(), e.getValue()));
    String query = queryStringEncoder.toString().replace("?", "");

    String date = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(ZonedDateTime.now(ZoneOffset.UTC));
    String contentSha256 = hex(sha256(body));
    String isoDate = date.substring(0, 8);

    headers.set(HOST, host);
    headers.set(X_AMZ_CONTENT_SHA256, contentSha256);
    headers.set(X_AMZ_DATE, date);

    List<String> headerSortedKeys = headers
        .entries()
        .stream()
        .map(Map.Entry::getKey)
        .map(String::toLowerCase)
        .sorted(Comparator.naturalOrder())
        .collect(Collectors.toList());

    List<String> hashedHeaders = new ArrayList<>();
    List<String> canonicalRequestLines = new ArrayList<>(Arrays.asList(method, path, query));
    headerSortedKeys.forEach(key -> {
      hashedHeaders.add(key);
      canonicalRequestLines.add(key + ":" + headers.get(key).trim());
    });

    String signedHeaders = String.join(";", hashedHeaders);

    canonicalRequestLines.addAll(List.of("", signedHeaders, contentSha256));

    String canonicalRequest = String.join("\n", canonicalRequestLines);

    String canonicalRequestHashedPayload = hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

    String credentialScope = String.format("%s/%s/%s/%s", isoDate, region, service, AWS4_REQUEST);

    String stringToSign = String.join("\n", AWS4_HMAC_SHA256, date, credentialScope, canonicalRequestHashedPayload);

    byte[] dateKey = hmacSHA256((String.format("%s%s", AWS4, secretKey)).getBytes(StandardCharsets.UTF_8), isoDate);
    byte[] dateRegionKey = hmacSHA256(dateKey, region);
    byte[] dateRegionServiceKey = hmacSHA256(dateRegionKey, service);
    byte[] signingKey = hmacSHA256(dateRegionServiceKey, AWS4_REQUEST);

    String signature = hex(hmacSHA256(signingKey, stringToSign));

    String authorization = String.format("%s Credential=%s/%s,SignedHeaders=%s,Signature=%s",
        AWS4_HMAC_SHA256, accessKey, credentialScope, signedHeaders, signature);

    headers.set(AUTHORIZATION, authorization);

  }

}
