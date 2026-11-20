package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.buffer.Buffer;

/** {@link S3Xml#parseError} across provider variants and non-error bodies. */
class ErrorDocumentTest {

  @Test
  void awsStyleError() {
    ErrorDocument error = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message>"
      + "<Key>photos/2006/February/sample.jpg</Key><RequestId>4442587FB7D0A2F9</RequestId>"
      + "<HostId>Uuag1LuByRx9e6j5Onimru9pO4ZVKnJ2Qz7/C1NPcfTWAtRPfTaOFg==</HostId></Error>");
    assertEquals("NoSuchKey", error.code());
    assertEquals("The specified key does not exist.", error.message());
    assertNull(error.resource());
    assertEquals("4442587FB7D0A2F9", error.requestId());
    assertEquals("Uuag1LuByRx9e6j5Onimru9pO4ZVKnJ2Qz7/C1NPcfTWAtRPfTaOFg==", error.hostId());
    assertEquals(Map.of("Key", "photos/2006/February/sample.jpg"), error.details());
  }

  @Test
  void siloStyleErrorAsReturnedByTheServer() {
    // Captured from Silo RELEASE.2026-11-12T00-00-00Z during Phase 2 step 1.
    ErrorDocument error = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>XMinioInvalidResourceName</Code>"
      + "<Message>Resource name contains bad components such as &#34;..&#34; or &#34;.&#34;.</Message>"
      + "<Resource>/keys-8118ed3a/dir//double/./dot/../x</Resource><Region>test-west-1</Region>"
      + "<RequestId>18E9B108015D7EC6</RequestId>"
      + "<HostId>dd9025bab4ad464b049177c95eb6ebf374d3b3fd1af9251148b658df7ac2e3e8</HostId></Error>");
    assertEquals("XMinioInvalidResourceName", error.code());
    assertEquals("Resource name contains bad components such as \"..\" or \".\".", error.message());
    assertEquals("/keys-8118ed3a/dir//double/./dot/../x", error.resource());
    assertEquals("18E9B108015D7EC6", error.requestId());
    assertEquals(Map.of("Region", "test-west-1"), error.details());
  }

  @Test
  void rgwStyleErrorWithoutMessage() {
    // Ceph RGW-style shape (as used by S3-compatible services such as DigitalOcean Spaces):
    // no Message, BucketName instead of Resource. Constructed, not captured.
    ErrorDocument error = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchBucket</Code>"
      + "<BucketName>missing</BucketName><RequestId>tx00000a1b2c3d4e5f6</RequestId><HostId>abc-nyc3</HostId></Error>");
    assertEquals("NoSuchBucket", error.code());
    assertNull(error.message());
    assertEquals("tx00000a1b2c3d4e5f6", error.requestId());
    assertEquals(Map.of("BucketName", "missing"), error.details());
  }

  @Test
  void permanentRedirectKeepsEndpointDetails() {
    ErrorDocument error = parse("<Error><Code>PermanentRedirect</Code>"
      + "<Message>The bucket you are attempting to access must be addressed using the specified endpoint.</Message>"
      + "<Endpoint>bucket.s3.eu-west-1.amazonaws.com</Endpoint><Bucket>bucket</Bucket>"
      + "<RequestId>R</RequestId><HostId>H</HostId></Error>");
    assertEquals("PermanentRedirect", error.code());
    assertEquals("bucket.s3.eu-west-1.amazonaws.com", error.details().get("Endpoint"));
    assertEquals("bucket", error.details().get("Bucket"));
  }

  @Test
  void unknownNestedElementsAreIgnoredAndNamespacesTolerated() {
    ErrorDocument error = parse("<Error xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Code>X</Code>"
      + "<Nested><Deep>v</Deep></Nested><Extra>e</Extra></Error>");
    assertEquals("X", error.code());
    assertEquals(Map.of("Extra", "e"), error.details());
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "",
    "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>",
    "{\"error\":\"json\"}",
    "<ListBucketResult><Name>b</Name></ListBucketResult>",
    "<Error><Code>Truncated",
    "<!DOCTYPE Error [<!ENTITY x \"y\">]><Error><Code>&x;</Code></Error>"
  })
  void nonErrorBodiesGiveNull(String body) {
    assertNull(parse(body));
  }

  @Test
  void nullBodyGivesNull() {
    assertNull(S3Xml.parseError((Buffer) null));
    assertNull(S3Xml.parseError((byte[]) null));
  }

  @Test
  void bufferOverloadParses() {
    assertNotNull(S3Xml.parseError(Buffer.buffer("<Error><Code>C</Code></Error>")));
  }

  private static ErrorDocument parse(String body) {
    return S3Xml.parseError(body.getBytes(StandardCharsets.UTF_8));
  }
}
