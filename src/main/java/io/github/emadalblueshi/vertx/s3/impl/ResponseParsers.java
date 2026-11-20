package io.github.emadalblueshi.vertx.s3.impl;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import io.github.emadalblueshi.vertx.s3.S3Bucket;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
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
