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
import java.util.stream.Stream;

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

    List<String> headerSortedKeys = headers
        .entries()
        .stream()
        .map(Map.Entry::getKey)
        .map(String::toLowerCase)
        .sorted(Comparator.naturalOrder())
        .collect(Collectors.toList());

    List<String> hashedHeaders = new ArrayList<>();
    List<String> canonicalRequestLines = Stream.of(method, path, query).collect(Collectors.toList());

    headerSortedKeys.forEach(key -> {
      hashedHeaders.add(key);
      canonicalRequestLines.add(key + ":" + headers.get(key).trim());
    });

    String signedHeaders = hashedHeaders.stream().collect(Collectors.joining(";"));

    canonicalRequestLines.addAll(Stream.of(null, signedHeaders, contentSha256).collect(Collectors.toList()));

    String canonicalRequestBody = canonicalRequestLines
        .stream()
        .map(line -> line == null ? "" : line)
        .collect(Collectors.joining("\n"));

    String canonicalRequestHash = hex(sha256(canonicalRequestBody.getBytes(StandardCharsets.UTF_8)));

    String credentialScope = String.format("%s/%s/%s/aws4_request", isoDate, region, service);

    String stringToSign = Stream.of("AWS4-HMAC-SHA256", date, credentialScope, canonicalRequestHash)
        .collect(Collectors.joining("\n"));

    byte[] dateKey = hmacSHA256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), isoDate);
    byte[] dateRegionkey = hmacSHA256(dateKey, region);
    byte[] dateRegionServiceKey = hmacSHA256(dateRegionkey, service);
    byte[] signingKey = hmacSHA256(dateRegionServiceKey, "aws4_request");

    String signature = hex(hmacSHA256(signingKey, stringToSign));

    String authorization = String.format("AWS4-HMAC-SHA256 Credential=%s/%s,SignedHeaders=%s,Signature=%s", accessKey,
        credentialScope, signedHeaders, signature);

    headers.set("authorization", authorization);

  }

}
