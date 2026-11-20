package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsResult;
import io.github.emadalblueshi.vertx.s3.S3ListedObject;
import io.vertx.core.buffer.Buffer;

class ResponseParsersTest {

  @Test
  void listBucketsAwsStyle() {
    S3ListBucketsResult result = ResponseParsers.listBuckets(Buffer.buffer(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        + "<Owner><ID>bcaf1ffd86f461ca5fb16fd081034f</ID><DisplayName>webfile</DisplayName></Owner>"
        + "<Buckets><Bucket><Name>quotes</Name><CreationDate>2006-02-03T16:45:09.000Z</CreationDate></Bucket>"
        + "<Bucket><Name>samples</Name><CreationDate>2006-02-03T16:41:58.000Z</CreationDate></Bucket></Buckets>"
        + "</ListAllMyBucketsResult>"));
    assertEquals("bcaf1ffd86f461ca5fb16fd081034f", result.getOwner().getId());
    assertEquals("webfile", result.getOwner().getDisplayName());
    assertEquals(2, result.getBuckets().size());
    assertEquals("quotes", result.getBuckets().get(0).getName());
    assertEquals(Instant.parse("2006-02-03T16:45:09Z"), result.getBuckets().get(0).getCreationDate());
  }

  @Test
  void listBucketsEmptyWithoutOwnerDisplayName() {
    S3ListBucketsResult result = ResponseParsers.listBuckets(Buffer.buffer(
      "<ListAllMyBucketsResult><Owner><ID>id</ID><DisplayName></DisplayName></Owner><Buckets></Buckets></ListAllMyBucketsResult>"));
    assertTrue(result.getBuckets().isEmpty());
    assertNull(result.getOwner().getDisplayName());
  }

  @Test
  void listBucketsWithoutOwnerOrBucketsElement() {
    S3ListBucketsResult result = ResponseParsers.listBuckets(Buffer.buffer("<ListAllMyBucketsResult/>"));
    assertNull(result.getOwner());
    assertTrue(result.getBuckets().isEmpty());
  }

  @Test
  void resultIsAJsonRoundTrippableDataObject() {
    S3ListBucketsResult result = ResponseParsers.listBuckets(Buffer.buffer(
      "<ListAllMyBucketsResult><Owner><ID>id</ID></Owner><Buckets><Bucket><Name>b</Name>"
        + "<CreationDate>2026-11-20T10:15:30.000Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>"));
    S3ListBucketsResult copy = new S3ListBucketsResult(result.toJson());
    assertEquals(result.toJson(), copy.toJson());
    assertEquals(Instant.parse("2026-11-20T10:15:30Z"), copy.getBuckets().get(0).getCreationDate());
  }

  @Test
  void wrongRootOrInvalidDateIsRejected() {
    assertThrows(XmlParseException.class, () -> ResponseParsers.listBuckets(Buffer.buffer("<Error><Code>X</Code></Error>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.listBuckets(Buffer.buffer(
      "<ListAllMyBucketsResult><Buckets><Bucket><Name>b</Name><CreationDate>yesterday</CreationDate></Bucket>"
        + "</Buckets></ListAllMyBucketsResult>")));
  }

  // --- ListObjectsV2 ---

  @Test
  void listObjectsDecodesUrlEncodedKeysAndPrefixes() {
    S3ListObjectsResult result = ResponseParsers.listObjects(Buffer.buffer(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        + "<Name>b</Name><Prefix>dir%2F</Prefix><KeyCount>3</KeyCount><MaxKeys>2</MaxKeys><Delimiter>%2F</Delimiter>"
        + "<EncodingType>url</EncodingType><IsTruncated>true</IsTruncated>"
        + "<Contents><Key>dir/a+b%2Bc.txt</Key><LastModified>2026-11-20T10:15:30.000Z</LastModified>"
        + "<ETag>&quot;e1&quot;</ETag><Size>12</Size><StorageClass>STANDARD</StorageClass></Contents>"
        + "<Contents><Key>dir/%D9%85%D9%84%D9%81%20100%25.txt</Key><Size>0</Size></Contents>"
        + "<CommonPrefixes><Prefix>dir/sub+folder/</Prefix></CommonPrefixes>"
        + "<NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=</NextContinuationToken>"
        + "</ListBucketResult>"));
    assertEquals(2, result.getObjects().size());
    S3ListedObject first = result.getObjects().get(0);
    assertEquals("dir/a b+c.txt", first.getKey(), "+ is a space, %2B a plus");
    assertEquals(12, first.getSize());
    assertEquals("\"e1\"", first.getEtag());
    assertEquals(Instant.parse("2026-11-20T10:15:30Z"), first.getLastModified());
    assertEquals("dir/\u0645\u0644\u0641 100%.txt", result.getObjects().get(1).getKey());
    assertNull(result.getObjects().get(1).getLastModified());
    assertEquals(List.of("dir/sub folder/"), result.getCommonPrefixes());
    assertTrue(result.isTruncated());
    assertEquals("1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=", result.getNextContinuationToken(), "tokens are opaque");
  }

  @Test
  void listObjectsTakesKeysAsTheyAreWithoutEncodingType() {
    S3ListObjectsResult result = ResponseParsers.listObjects(Buffer.buffer(
      "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>a+b%20c</Key><Size>1</Size></Contents>"
        + "</ListBucketResult>"));
    assertEquals("a+b%20c", result.getObjects().get(0).getKey());
    assertFalse(result.isTruncated());
    assertNull(result.getNextContinuationToken());
  }

  @Test
  void listObjectsOfAnEmptyBucket() {
    S3ListObjectsResult result = ResponseParsers.listObjects(Buffer.buffer(
      "<ListBucketResult><Name>b</Name><KeyCount>0</KeyCount><EncodingType>url</EncodingType>"
        + "<IsTruncated>false</IsTruncated></ListBucketResult>"));
    assertTrue(result.getObjects().isEmpty());
    assertTrue(result.getCommonPrefixes().isEmpty());
    assertFalse(result.isTruncated());
  }

  @Test
  void listObjectsRejectsInvalidResponses() {
    assertThrows(XmlParseException.class, () -> ResponseParsers.listObjects(Buffer.buffer("<ListAllMyBucketsResult/>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.listObjects(Buffer.buffer(
      "<ListBucketResult><EncodingType>url</EncodingType><Contents><Key>bad%zz</Key></Contents></ListBucketResult>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.listObjects(Buffer.buffer(
      "<ListBucketResult><Contents><Size>1</Size></Contents></ListBucketResult>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.listObjects(Buffer.buffer(
      "<ListBucketResult><Contents><Key>k</Key><Size>big</Size></Contents></ListBucketResult>")));
  }
}
