package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
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
}
