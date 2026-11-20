package io.github.emadalblueshi.vertx.s3.impl;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import io.github.emadalblueshi.vertx.s3.S3Bucket;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsResult;
import io.github.emadalblueshi.vertx.s3.S3ListedObject;
import io.github.emadalblueshi.vertx.s3.S3Owner;
import io.vertx.core.buffer.Buffer;

/**
 * Maps S3 XML response bodies to API result types. Operations add their parsers here.
 */
final class ResponseParsers {

  private ResponseParsers() {
  }

  /**
   * {@code ListAllMyBucketsResult}.
   *
   * @throws XmlParseException if the body is not a valid ListBuckets response
   */
  static S3ListBucketsResult listBuckets(Buffer body) {
    XmlElement root = S3Xml.parse(body);
    expectRoot(root, "ListAllMyBucketsResult");
    S3ListBucketsResult result = new S3ListBucketsResult();
    result.setOwner(owner(root.child("Owner")));
    List<S3Bucket> buckets = new ArrayList<>();
    XmlElement container = root.child("Buckets");
    if (container != null) {
      for (XmlElement bucket : container.children("Bucket")) {
        buckets.add(new S3Bucket()
          .setName(bucket.childText("Name"))
          .setCreationDate(instant(bucket.childText("CreationDate"))));
      }
    }
    return result.setBuckets(buckets);
  }

  /**
   * {@code ListBucketResult} of ListObjectsV2. Keys and prefixes are decoded when the response
   * says {@code <EncodingType>url</EncodingType>}: form-style, so {@code +} is a space (what
   * MinIO-compatible servers send) and {@code %XX} sequences are UTF-8. Without it they are taken
   * as they are, so a server that ignores {@code encoding-type} can't have keys mangled.
   *
   * @throws XmlParseException if the body is not a valid ListObjectsV2 response
   */
  static S3ListObjectsResult listObjects(Buffer body) {
    XmlElement root = S3Xml.parse(body);
    expectRoot(root, "ListBucketResult");
    boolean urlEncoded = "url".equalsIgnoreCase(String.valueOf(root.childText("EncodingType")).trim());
    List<S3ListedObject> objects = new ArrayList<>();
    for (XmlElement contents : root.children("Contents")) {
      String key = contents.childText("Key");
      if (key == null) {
        throw new XmlParseException("<Contents> without <Key>");
      }
      objects.add(new S3ListedObject()
        .setKey(decode(key, urlEncoded))
        .setSize(size(contents.childText("Size")))
        .setEtag(emptyToNull(contents.childText("ETag")))
        .setLastModified(instant(contents.childText("LastModified"))));
    }
    List<String> commonPrefixes = new ArrayList<>();
    for (XmlElement common : root.children("CommonPrefixes")) {
      String prefix = common.childText("Prefix");
      if (prefix != null) {
        commonPrefixes.add(decode(prefix, urlEncoded));
      }
    }
    return new S3ListObjectsResult()
      .setObjects(objects)
      .setCommonPrefixes(commonPrefixes)
      .setTruncated("true".equalsIgnoreCase(String.valueOf(root.childText("IsTruncated")).trim()))
      .setNextContinuationToken(emptyToNull(root.childText("NextContinuationToken")));
  }

  private static String decode(String value, boolean urlEncoded) {
    if (!urlEncoded) {
      return value;
    }
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new XmlParseException("Invalid URL-encoded value: " + value, e);
    }
  }

  private static long size(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new XmlParseException("Invalid size: " + value, e);
    }
  }

  private static S3Owner owner(XmlElement owner) {
    if (owner == null) {
      return null;
    }
    return new S3Owner().setId(owner.childText("ID")).setDisplayName(emptyToNull(owner.childText("DisplayName")));
  }

  /** ISO-8601 timestamps as S3 writes them, e.g. {@code 2026-11-20T10:15:30.000Z}. */
  private static Instant instant(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new XmlParseException("Invalid timestamp: " + value, e);
    }
  }

  private static void expectRoot(XmlElement root, String name) {
    if (!name.equals(root.name())) {
      throw new XmlParseException("Expected <" + name + "> but got <" + root.name() + ">");
    }
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
