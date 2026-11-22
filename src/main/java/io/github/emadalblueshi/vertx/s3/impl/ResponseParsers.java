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
import io.github.emadalblueshi.vertx.s3.S3MultipartUpload;
import io.github.emadalblueshi.vertx.s3.S3Owner;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.S3UploadedPart;
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

  /**
   * The {@code UploadId} of an {@code InitiateMultipartUploadResult}.
   *
   * @throws XmlParseException if the body is not a valid CreateMultipartUpload response
   */
  static String createMultipartUpload(Buffer body) {
    XmlElement root = S3Xml.parse(body);
    expectRoot(root, "InitiateMultipartUploadResult");
    String uploadId = emptyToNull(root.childText("UploadId"));
    if (uploadId == null) {
      throw new XmlParseException("<InitiateMultipartUploadResult> without <UploadId>");
    }
    return uploadId;
  }

  /**
   * The {@code ETag} of a {@code CompleteMultipartUploadResult}. S3 can report a failure after it
   * has started a {@code 200} response, padding the body with whitespace while it works: leading
   * whitespace is ignored, and an {@code <Error>} document becomes an {@link S3ServiceException}
   * with the response's status code.
   *
   * @throws S3ServiceException if the body is an {@code <Error>} document
   * @throws XmlParseException if the body is not a valid CompleteMultipartUpload response
   */
  static String completeMultipartUpload(S3Response response) {
    Buffer body = withoutLeadingWhitespace(response.body);
    XmlElement root = S3Xml.parse(body);
    if ("Error".equals(root.name())) {
      throw ErrorMapper.toException(new S3Response(response.statusCode, response.statusMessage, response.headers, body));
    }
    expectRoot(root, "CompleteMultipartUploadResult");
    String etag = emptyToNull(root.childText("ETag"));
    if (etag == null) {
      throw new XmlParseException("<CompleteMultipartUploadResult> without <ETag>");
    }
    return etag;
  }

  /** One page of a {@code ListPartsResult}. */
  static final class PartsPage {
    final List<S3UploadedPart> parts;
    final boolean truncated;
    /** The marker for the next page, or {@code null} if absent. */
    final Integer nextPartNumberMarker;

    PartsPage(List<S3UploadedPart> parts, boolean truncated, Integer nextPartNumberMarker) {
      this.parts = parts;
      this.truncated = truncated;
      this.nextPartNumberMarker = nextPartNumberMarker;
    }
  }

  /**
   * One page of a {@code ListPartsResult}.
   *
   * @throws XmlParseException if the body is not a valid ListParts response
   */
  static PartsPage listPartsPage(Buffer body) {
    XmlElement root = S3Xml.parse(body);
    expectRoot(root, "ListPartsResult");
    List<S3UploadedPart> parts = new ArrayList<>();
    for (XmlElement part : root.children("Part")) {
      parts.add(new S3UploadedPart()
        .setPartNumber(partNumber(part.childText("PartNumber")))
        .setEtag(emptyToNull(part.childText("ETag")))
        .setSize(size(part.childText("Size")))
        .setLastModified(instant(part.childText("LastModified"))));
    }
    String next = emptyToNull(root.childText("NextPartNumberMarker"));
    return new PartsPage(parts, truncated(root), next == null ? null : partNumber(next));
  }

  /** One page of a {@code ListMultipartUploadsResult}. */
  static final class UploadsPage {
    final List<S3MultipartUpload> uploads;
    final boolean truncated;
    /** The markers for the next page (decoded), or {@code null} if absent. */
    final String nextKeyMarker;
    final String nextUploadIdMarker;

    UploadsPage(List<S3MultipartUpload> uploads, boolean truncated, String nextKeyMarker, String nextUploadIdMarker) {
      this.uploads = uploads;
      this.truncated = truncated;
      this.nextKeyMarker = nextKeyMarker;
      this.nextUploadIdMarker = nextUploadIdMarker;
    }
  }

  /**
   * One page of a {@code ListMultipartUploadsResult}. Keys and the key marker are decoded as in
   * {@link #listObjects} when the response says {@code <EncodingType>url</EncodingType>}.
   *
   * @throws XmlParseException if the body is not a valid ListMultipartUploads response
   */
  static UploadsPage listMultipartUploadsPage(Buffer body) {
    XmlElement root = S3Xml.parse(body);
    expectRoot(root, "ListMultipartUploadsResult");
    boolean urlEncoded = "url".equalsIgnoreCase(String.valueOf(root.childText("EncodingType")).trim());
    List<S3MultipartUpload> uploads = new ArrayList<>();
    for (XmlElement upload : root.children("Upload")) {
      String key = upload.childText("Key");
      String uploadId = emptyToNull(upload.childText("UploadId"));
      if (key == null || uploadId == null) {
        throw new XmlParseException("<Upload> without <Key> or <UploadId>");
      }
      uploads.add(new S3MultipartUpload()
        .setKey(decode(key, urlEncoded))
        .setUploadId(uploadId)
        .setInitiated(instant(upload.childText("Initiated"))));
    }
    String nextKey = emptyToNull(root.childText("NextKeyMarker"));
    return new UploadsPage(uploads, truncated(root), nextKey == null ? null : decode(nextKey, urlEncoded),
      emptyToNull(root.childText("NextUploadIdMarker")));
  }

  private static boolean truncated(XmlElement root) {
    return "true".equalsIgnoreCase(String.valueOf(root.childText("IsTruncated")).trim());
  }

  private static int partNumber(String value) {
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new XmlParseException("Invalid part number: " + value, e);
    }
  }

  private static Buffer withoutLeadingWhitespace(Buffer body) {
    int start = 0;
    while (start < body.length() && Character.isWhitespace(body.getByte(start))) {
      start++;
    }
    return start == 0 ? body : body.slice(start, body.length());
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
