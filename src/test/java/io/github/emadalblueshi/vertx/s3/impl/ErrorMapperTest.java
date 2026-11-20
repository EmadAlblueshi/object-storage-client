package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/** Error responses to {@link S3ServiceException}. */
class ErrorMapperTest {

  @Test
  void errorDocument() {
    S3ServiceException e = map(403, "Forbidden", headers("x-amz-request-id", "HEADER-ID"),
      "<Error><Code>AccessDenied</Code><Message>Access Denied</Message><Resource>/b/k</Resource>"
        + "<RequestId>DOC-ID</RequestId><HostId>HOST</HostId><BucketName>b</BucketName></Error>");
    assertEquals(403, e.getStatusCode());
    assertEquals("AccessDenied", e.getErrorCode());
    assertEquals("AccessDenied: Access Denied (HTTP 403 Forbidden)", e.getMessage());
    assertEquals("DOC-ID", e.getRequestId(), "the document's request id wins");
    assertEquals("HOST", e.getHostId());
    assertEquals("/b/k", e.getResource());
    assertEquals(Map.of("BucketName", "b"), e.getDetails());
  }

  @Test
  void bodilessErrorUsesStatusAndHeaders() {
    S3ServiceException e = map(404, "Not Found", headers("x-amz-request-id", "R1", "x-amz-id-2", "H1"), "");
    assertEquals(404, e.getStatusCode());
    assertNull(e.getErrorCode());
    assertEquals("HTTP 404 Not Found (no error document)", e.getMessage());
    assertEquals("R1", e.getRequestId());
    assertEquals("H1", e.getHostId());
    assertTrue(e.getDetails().isEmpty());
  }

  @Test
  void notModifiedAndPreconditionFailed() {
    assertEquals(304, map(304, "Not Modified", headers(), "").getStatusCode());
    assertEquals("HTTP 412 Precondition Failed (no error document)",
      map(412, "Precondition Failed", headers(), "").getMessage());
  }

  @Test
  void htmlErrorPageGivesStatusAndSanitisedExcerpt() {
    S3ServiceException e = map(502, "Bad Gateway", headers(),
      "<html>\r\n<head><title>502 Bad Gateway</title></head>\n\t<body>nginx</body></html>");
    assertEquals(502, e.getStatusCode());
    assertNull(e.getErrorCode());
    assertEquals("HTTP 502 Bad Gateway: <html> <head><title>502 Bad Gateway</title></head> <body>nginx</body></html>",
      e.getMessage());
  }

  @Test
  void longBodiesAreTruncated() {
    String body = "x".repeat(ErrorMapper.MAX_EXCERPT * 10);
    S3ServiceException e = map(500, "Internal Server Error", headers(), body);
    assertTrue(e.getMessage().endsWith("..."), e.getMessage());
    assertTrue(e.getMessage().length() < ErrorMapper.MAX_EXCERPT + 60, e.getMessage());
  }

  @Test
  void permanentRedirectKeepsEndpointAndBucketRegion() {
    S3ServiceException e = map(301, "Moved Permanently", headers("x-amz-bucket-region", "eu-west-1"),
      "<Error><Code>PermanentRedirect</Code><Message>Use the specified endpoint.</Message>"
        + "<Endpoint>b.s3.eu-west-1.amazonaws.com</Endpoint><Bucket>b</Bucket></Error>");
    assertEquals(301, e.getStatusCode());
    assertEquals("PermanentRedirect", e.getErrorCode());
    assertEquals("b.s3.eu-west-1.amazonaws.com", e.getDetails().get("Endpoint"));
    assertEquals("eu-west-1", e.getDetails().get("BucketRegion"));
  }

  @Test
  void documentWithOnlyCodeOrWithEmptyMessage() {
    assertEquals("NoSuchBucket (HTTP 404 Not Found)",
      map(404, "Not Found", headers(), "<Error><Code>NoSuchBucket</Code><Message></Message></Error>").getMessage());
    assertNull(map(400, "Bad Request", headers(), "<Error><Code></Code></Error>").getErrorCode());
  }

  @Test
  void jsonBodyIsTreatedAsNonXml() {
    S3ServiceException e = map(400, "Bad Request", headers(), "{\"message\":\"bad\"}");
    assertNull(e.getErrorCode());
    assertEquals("HTTP 400 Bad Request: {\"message\":\"bad\"}", e.getMessage());
  }

  private static S3ServiceException map(int status, String message, MultiMap headers, String body) {
    return ErrorMapper.toException(new S3Response(status, message, headers, Buffer.buffer(body)));
  }

  private static MultiMap headers(String... nameValues) {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    for (int i = 0; i < nameValues.length; i += 2) {
      headers.add(nameValues[i], nameValues[i + 1]);
    }
    return headers;
  }
}
