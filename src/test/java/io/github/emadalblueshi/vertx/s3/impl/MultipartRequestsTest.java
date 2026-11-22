package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3MultipartUpload;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/** Multipart checks, the CompleteMultipartUpload body, and the multipart response parsers. */
class MultipartRequestsTest {

  // --- checks ------------------------------------------------------------------------------------------

  @Test
  void uploadIdsMustBeNonEmptyUnicode() {
    assertNull(MultipartRequests.uploadIdProblem("MDlhODk2YTUt.Zjk-_~"));
    assertEquals("The upload id is required", MultipartRequests.uploadIdProblem(null));
    assertEquals("The upload id is required", MultipartRequests.uploadIdProblem(""));
    assertTrue(MultipartRequests.uploadIdProblem("bad\uD800").contains("Unicode"));
  }

  @Test
  void partNumbersAreOneToTenThousand() {
    assertNull(MultipartRequests.partNumberProblem(1));
    assertNull(MultipartRequests.partNumberProblem(10_000));
    assertEquals("The part number must be 1 to 10000: 0", MultipartRequests.partNumberProblem(0));
    assertEquals("The part number must be 1 to 10000: 10001", MultipartRequests.partNumberProblem(10_001));
    assertEquals("The part number must be 1 to 10000: -1", MultipartRequests.partNumberProblem(-1));
  }

  @Test
  void prefixesMayBeNullButMustBeValidUnicode() {
    assertNull(MultipartRequests.prefixProblem(null));
    assertNull(MultipartRequests.prefixProblem("videos/مستند/"));
    assertTrue(MultipartRequests.prefixProblem("bad\uDC00").contains("Unicode"));
  }

  /** File uploads: the smallest multiple of the minimum that fits the file in the part limit. */
  @Test
  void partSizeGrowsWithTheFileInMultiplesOfTheMinimum() {
    int mib8 = 8 * 1024 * 1024;
    assertEquals(mib8, MultipartRequests.partSizeFor(0, mib8, 10_000));
    assertEquals(mib8, MultipartRequests.partSizeFor(1, mib8, 10_000));
    assertEquals(mib8, MultipartRequests.partSizeFor(10_000L * mib8, mib8, 10_000), "78 GiB");
    assertEquals(2 * mib8, MultipartRequests.partSizeFor(10_000L * mib8 + 1, mib8, 10_000));
    assertEquals(66 * mib8, MultipartRequests.partSizeFor(MultipartRequests.MAX_OBJECT_SIZE, mib8, 10_000), "5 TiB: 528 MiB");
    assertEquals(8, MultipartRequests.partSizeFor(10, 4, 2));
    assertThrows(IllegalArgumentException.class, () -> MultipartRequests.partSizeFor(Long.MAX_VALUE / 2, mib8, 10_000));
  }

  // --- CompleteMultipartUpload body --------------------------------------------------------------------

  @Test
  void completeBodyListsThePartsInAscendingOrderAndEscapesETags() {
    Buffer body = MultipartRequests.completeBody(List.of(new S3CompletedPart(3, "\"c\""), new S3CompletedPart(1, "a&b"),
      new S3CompletedPart(2, "\"b\"")));
    assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
      + "<Part><PartNumber>1</PartNumber><ETag>a&amp;b</ETag></Part>"
      + "<Part><PartNumber>2</PartNumber><ETag>\"b\"</ETag></Part>"
      + "<Part><PartNumber>3</PartNumber><ETag>\"c\"</ETag></Part>"
      + "</CompleteMultipartUpload>", body.toString());
  }

  @Test
  void partNumbersMayHaveGaps() {
    assertTrue(MultipartRequests.completeBody(List.of(new S3CompletedPart(10_000, "z"), new S3CompletedPart(1, "a")))
      .toString().contains("<PartNumber>1</PartNumber><ETag>a</ETag></Part><Part><PartNumber>10000</PartNumber>"));
  }

  @Test
  void invalidPartListsAreRejected() {
    assertRejected(null, "At least one part is required");
    assertRejected(List.of(), "At least one part is required");
    assertRejected(Arrays.asList(new S3CompletedPart(1, "a"), null), "A part is null");
    assertRejected(List.of(new S3CompletedPart(0, "a")), "The part number must be 1 to 10000: 0");
    assertRejected(List.of(new S3CompletedPart(10_001, "a")), "The part number must be 1 to 10000: 10001");
    assertRejected(List.of(new S3CompletedPart(2, "a"), new S3CompletedPart(2, "b")), "Part 2 is listed more than once");
    assertRejected(List.of(new S3CompletedPart(1, null)), "Part 1 needs its ETag");
    assertRejected(List.of(new S3CompletedPart(1, "")), "Part 1 needs its ETag");
    assertRejected(List.of(new S3CompletedPart(1, "é")), "Part 1 needs its ETag");
    assertRejected(List.of(new S3CompletedPart(1, "a\r\nb")), "Part 1 needs its ETag");
    List<S3CompletedPart> tooMany = new ArrayList<>();
    for (int i = 1; i <= 10_001; i++) {
      tooMany.add(new S3CompletedPart(i, "e"));
    }
    assertRejected(tooMany, "At most 10000 parts are allowed: 10001");
    assertEquals(10_000, MultipartRequests.completeBody(tooMany.subList(0, 10_000)).toString().split("<Part>").length - 1);
  }

  private static void assertRejected(List<S3CompletedPart> parts, String messageStart) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MultipartRequests.completeBody(parts));
    assertTrue(e.getMessage().startsWith(messageStart), e.getMessage());
  }

  // --- parsers -----------------------------------------------------------------------------------------

  @Test
  void createResultGivesTheUploadId() {
    assertEquals("up-1", ResponseParsers.createMultipartUpload(Buffer.buffer("<InitiateMultipartUploadResult><Bucket>b</Bucket>"
      + "<Key>k</Key><UploadId>up-1</UploadId></InitiateMultipartUploadResult>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.createMultipartUpload(Buffer.buffer(
      "<InitiateMultipartUploadResult><UploadId></UploadId></InitiateMultipartUploadResult>")));
    assertThrows(XmlParseException.class, () -> ResponseParsers.createMultipartUpload(Buffer.buffer("<Other/>")));
  }

  @Test
  void completeResultGivesTheETagEvenAfterWhitespacePadding() {
    String result = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult><ETag>\"abc-2\"</ETag>"
      + "</CompleteMultipartUploadResult>";
    assertEquals("\"abc-2\"", ResponseParsers.completeMultipartUpload(response(200, result)));
    assertEquals("\"abc-2\"", ResponseParsers.completeMultipartUpload(response(200, "\n \r\n\t  " + result)));
    assertThrows(XmlParseException.class, () -> ResponseParsers.completeMultipartUpload(response(200,
      "<CompleteMultipartUploadResult></CompleteMultipartUploadResult>")));
  }

  /** S3 can fail a CompleteMultipartUpload after starting a 200 response: the body is then an error document. */
  @Test
  void completeErrorInsideA200ResponseIsAServiceException() {
    S3ServiceException e = assertThrows(S3ServiceException.class, () -> ResponseParsers.completeMultipartUpload(response(200,
      "   \n<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>InternalError</Code><Message>We encountered an internal "
        + "error. Please try again.</Message><RequestId>req-1</RequestId><HostId>host-1</HostId></Error>")));
    assertEquals(200, e.getStatusCode());
    assertEquals("InternalError", e.getErrorCode());
    assertEquals("req-1", e.getRequestId());
    assertTrue(e.getMessage().startsWith("InternalError: We encountered an internal error"), e.getMessage());
  }

  @Test
  void listPartsPageGivesThePartsAndTheNextMarker() {
    ResponseParsers.PartsPage page = ResponseParsers.listPartsPage(Buffer.buffer("<ListPartsResult><IsTruncated>true</IsTruncated>"
      + "<NextPartNumberMarker>2</NextPartNumberMarker>"
      + "<Part><PartNumber>1</PartNumber><LastModified>2026-11-22T10:15:30.000Z</LastModified><ETag>\"e1\"</ETag><Size>5242880</Size></Part>"
      + "<Part><PartNumber>2</PartNumber><ETag>\"e2\"</ETag><Size>7</Size></Part></ListPartsResult>"));
    assertTrue(page.truncated);
    assertEquals(2, page.nextPartNumberMarker);
    assertEquals(2, page.parts.size());
    assertEquals(1, page.parts.get(0).getPartNumber());
    assertEquals("\"e1\"", page.parts.get(0).getEtag());
    assertEquals(5_242_880L, page.parts.get(0).getSize());
    assertEquals(Instant.parse("2026-11-22T10:15:30Z"), page.parts.get(0).getLastModified());
    assertNull(page.parts.get(1).getLastModified());

    ResponseParsers.PartsPage last = ResponseParsers.listPartsPage(Buffer.buffer("<ListPartsResult><IsTruncated>false</IsTruncated>"
      + "<NextPartNumberMarker></NextPartNumberMarker></ListPartsResult>"));
    assertFalse(last.truncated);
    assertNull(last.nextPartNumberMarker);
    assertEquals(List.of(), last.parts);
    assertThrows(XmlParseException.class, () -> ResponseParsers.listPartsPage(Buffer.buffer(
      "<ListPartsResult><Part><PartNumber>x</PartNumber></Part></ListPartsResult>")));
  }

  @Test
  void listUploadsPageDecodesKeysAndTheKeyMarker() {
    ResponseParsers.UploadsPage page = ResponseParsers.listMultipartUploadsPage(Buffer.buffer("<ListMultipartUploadsResult>"
      + "<EncodingType>url</EncodingType><IsTruncated>true</IsTruncated><NextKeyMarker>a+b/%D9%85.mp4</NextKeyMarker>"
      + "<NextUploadIdMarker>u2</NextUploadIdMarker>"
      + "<Upload><Key>a+b/%D9%85.mp4</Key><UploadId>u2</UploadId><Initiated>2026-11-22T10:15:30.000Z</Initiated></Upload>"
      + "</ListMultipartUploadsResult>"));
    assertTrue(page.truncated);
    assertEquals("a b/م.mp4", page.nextKeyMarker);
    assertEquals("u2", page.nextUploadIdMarker);
    S3MultipartUpload upload = page.uploads.get(0);
    assertEquals("a b/م.mp4", upload.getKey());
    assertEquals("u2", upload.getUploadId());
    assertEquals(Instant.parse("2026-11-22T10:15:30Z"), upload.getInitiated());

    // Without <EncodingType>url</EncodingType>, keys are taken as they are
    assertEquals("a+b", ResponseParsers.listMultipartUploadsPage(Buffer.buffer("<ListMultipartUploadsResult>"
      + "<Upload><Key>a+b</Key><UploadId>u</UploadId></Upload></ListMultipartUploadsResult>")).uploads.get(0).getKey());
    assertThrows(XmlParseException.class, () -> ResponseParsers.listMultipartUploadsPage(Buffer.buffer(
      "<ListMultipartUploadsResult><Upload><Key>k</Key></Upload></ListMultipartUploadsResult>")));
  }

  private static S3Response response(int status, String body) {
    return new S3Response(status, "OK", MultiMap.caseInsensitiveMultiMap(), Buffer.buffer(body));
  }
}
