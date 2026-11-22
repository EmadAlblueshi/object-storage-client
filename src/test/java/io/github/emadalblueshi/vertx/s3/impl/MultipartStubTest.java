package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3MultipartUpload;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.S3UploadedPart;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * The multipart operations against a local HTTP stub: what goes on the wire, the CompleteMultipartUpload
 * error inside a 200 response, paging across stubbed pages, the paging guards, and checks that fail
 * before any request. Against real servers, see the multipart integration suite.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultipartStubTest {

  private static final String NO_SUCH_UPLOAD = "<Error><Code>NoSuchUpload</Code><Message>The specified upload does not "
    + "exist.</Message><RequestId>r</RequestId></Error>";

  private Vertx vertx;
  private HttpServer http;
  private final AtomicReference<Function<Captured, Reply>> responder = new AtomicReference<>();
  private final List<Captured> requests = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  void startStub(Vertx vertx, VertxTestContext testContext) {
    this.vertx = vertx;
    vertx.createHttpServer().requestHandler(this::handle).listen(0, "localhost")
      .onComplete(testContext.succeeding(server -> {
        http = server;
        testContext.completeNow();
      }));
  }

  @AfterAll
  void stopStub(VertxTestContext testContext) {
    http.close().onComplete(testContext.succeedingThenComplete());
  }

  @BeforeEach
  void reset() {
    requests.clear();
    responder.set(c -> new Reply(500, "unexpected request"));
  }

  private void handle(HttpServerRequest request) {
    request.body().onSuccess(body -> {
      Captured captured = new Captured(request.method(), request.uri(),
        MultiMap.caseInsensitiveMultiMap().addAll(request.headers()), body);
      requests.add(captured);
      Reply reply = responder.get().apply(captured);
      request.response().setStatusCode(reply.status).end(reply.body);
    });
  }

  // --- wire format ---------------------------------------------------------------------------------

  @Test
  void createSendsAPostWithTheObjectOptions(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "<InitiateMultipartUploadResult><UploadId>up/1+=</UploadId></InitiateMultipartUploadResult>"));
    S3Client client = client();
    client.createMultipartUpload("bucket", "videos/a b.mp4", new S3PutObjectOptions().setContentType("video/mp4")
        .putUserMetadata("owner", "42"))
      .eventually(client::close)
      .onComplete(testContext.succeeding(uploadId -> testContext.verify(() -> {
        assertEquals("up/1+=", uploadId);
        Captured c = requests.get(0);
        assertEquals(HttpMethod.POST, c.method);
        assertEquals("/bucket/videos/a%20b.mp4?uploads=", c.uri);
        assertEquals("video/mp4", c.headers.get("Content-Type"));
        assertEquals("42", c.headers.get("x-amz-meta-owner"));
        assertEquals("0", c.headers.get("Content-Length"));
        assertTrue(c.headers.get("Authorization").contains("SignedHeaders=content-type;host;"), c.headers.get("Authorization"));
        testContext.completeNow();
      })));
  }

  @Test
  void completeSendsThePartsInAscendingOrderAndReturnsTheETag(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "<CompleteMultipartUploadResult><ETag>\"etag-3\"</ETag></CompleteMultipartUploadResult>"));
    S3Client client = client();
    client.completeMultipartUpload("bucket", "k", "up/1+=", List.of(new S3CompletedPart(3, "\"c\""),
        new S3CompletedPart(1, "\"a\""), new S3CompletedPart(2, "\"b\"")))
      .eventually(client::close)
      .onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
        assertEquals("\"etag-3\"", etag);
        Captured c = requests.get(0);
        assertEquals(HttpMethod.POST, c.method);
        assertEquals("/bucket/k?uploadId=up%2F1%2B%3D", c.uri);
        String body = c.body.toString();
        assertTrue(body.indexOf("<PartNumber>1</PartNumber>") < body.indexOf("<PartNumber>2</PartNumber>")
          && body.indexOf("<PartNumber>2</PartNumber>") < body.indexOf("<PartNumber>3</PartNumber>"), body);
        assertEquals(Integer.toString(c.body.length()), c.headers.get("Content-Length"));
        testContext.completeNow();
      })));
  }

  /** S3 can fail a CompleteMultipartUpload after starting a 200 response, padding the body with whitespace. */
  @Test
  void completeErrorInsideA200ResponseFails(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "\n   \n<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>InternalError</Code>"
      + "<Message>We encountered an internal error. Please try again.</Message><RequestId>req-9</RequestId></Error>"));
    S3Client client = client();
    client.completeMultipartUpload("bucket", "k", "up-1", List.of(new S3CompletedPart(1, "\"a\"")))
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(200, service.getStatusCode());
        assertEquals("InternalError", service.getErrorCode());
        assertEquals("req-9", service.getRequestId());
        testContext.completeNow();
      })));
  }

  @Test
  void completePaddedWithWhitespaceSucceeds(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "  \n \n<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult>"
      + "<ETag>\"e\"</ETag></CompleteMultipartUploadResult>"));
    S3Client client = client();
    client.completeMultipartUpload("bucket", "k", "up-1", List.of(new S3CompletedPart(1, "\"a\"")))
      .eventually(client::close)
      .onComplete(testContext.succeeding(etag -> testContext.verify(() -> {
        assertEquals("\"e\"", etag);
        testContext.completeNow();
      })));
  }

  @Test
  void abortSendsADeleteAndPassesNoSuchUploadThrough(VertxTestContext testContext) {
    responder.set(c -> c.uri.endsWith("uploadId=gone") ? new Reply(404, NO_SUCH_UPLOAD) : new Reply(204, ""));
    S3Client client = client();
    client.abortMultipartUpload("bucket", "k", "up-1")
      .compose(v -> {
        Captured c = requests.get(0);
        assertEquals(HttpMethod.DELETE, c.method);
        assertEquals("/bucket/k?uploadId=up-1", c.uri);
        return client.abortMultipartUpload("bucket", "k", "gone");
      })
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        S3ServiceException service = assertInstanceOf(S3ServiceException.class, e);
        assertEquals(404, service.getStatusCode());
        assertEquals("NoSuchUpload", service.getErrorCode());
        testContext.completeNow();
      })));
  }

  // --- paging --------------------------------------------------------------------------------------

  @Test
  void listPartsFollowsTheMarkersAcrossPages(VertxTestContext testContext) {
    responder.set(c -> {
      if (c.uri.contains("part-number-marker=4")) {
        return new Reply(200, partsPage(false, null, 5));
      }
      if (c.uri.contains("part-number-marker=2")) {
        return new Reply(200, partsPage(true, 4, 3, 4));
      }
      return new Reply(200, partsPage(true, 2, 1, 2));
    });
    S3Client client = client();
    client.listParts("bucket", "k", "up-1")
      .eventually(client::close)
      .onComplete(testContext.succeeding(parts -> testContext.verify(() -> {
        assertEquals(List.of(1, 2, 3, 4, 5), parts.stream().map(S3UploadedPart::getPartNumber).collect(Collectors.toList()));
        assertEquals("\"e5\"", parts.get(4).getEtag());
        assertEquals(List.of("/bucket/k?max-parts=1000&uploadId=up-1",
            "/bucket/k?max-parts=1000&part-number-marker=2&uploadId=up-1",
            "/bucket/k?max-parts=1000&part-number-marker=4&uploadId=up-1"),
          requests.stream().map(r -> r.uri).collect(Collectors.toList()));
        testContext.completeNow();
      })));
  }

  @Test
  void listPartsFailsWhenTheMarkerIsMissingOrDoesNotAdvance(VertxTestContext testContext) {
    responder.set(c -> c.uri.contains("part-number-marker=3") ? new Reply(200, partsPage(true, 3, 3))
      : new Reply(200, partsPage(true, null, 1)));
    S3Client client = client();
    client.listParts("bucket", "k", "up-1")
      .transform(missing -> {
        testContext.verify(() -> assertEquals("ListParts response is truncated but its next part number marker doesn't advance",
          missing.cause().getMessage()));
        responder.set(c -> c.uri.contains("part-number-marker=3") ? new Reply(200, partsPage(true, 3, 3))
          : new Reply(200, partsPage(true, 3, 1, 2, 3)));
        return client.listParts("bucket", "k", "up-1");
      })
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertInstanceOf(S3ClientException.class, e);
        assertEquals("ListParts response is truncated but its next part number marker doesn't advance", e.getMessage());
        testContext.completeNow();
      })));
  }

  @Test
  void listPartsStopsAfterTheMostPagesTenThousandPartsCanNeed(VertxTestContext testContext) {
    responder.set(c -> {
      int marker = c.uri.contains("part-number-marker=") ? Integer.parseInt(c.uri.replaceAll(".*part-number-marker=(\\d+).*", "$1")) : 0;
      return new Reply(200, partsPage(true, marker + 1, marker + 1));
    });
    S3ClientImpl client = (S3ClientImpl) client();
    // Pages of 5,000 parts: 10,000 parts need at most 2 pages
    client.listParts("bucket", "k", "up-1", 5000)
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("ListParts returned more than 2 pages", e.getMessage());
        assertEquals(2, requests.size());
        testContext.completeNow();
      })));
  }

  @Test
  void listUploadsFollowsBothMarkersAndDecodesKeys(VertxTestContext testContext) {
    responder.set(c -> c.uri.contains("upload-id-marker=u2")
      ? new Reply(200, "<ListMultipartUploadsResult><EncodingType>url</EncodingType><IsTruncated>false</IsTruncated>"
        + upload("a+b/2.mp4", "u3") + "</ListMultipartUploadsResult>")
      : new Reply(200, "<ListMultipartUploadsResult><EncodingType>url</EncodingType><IsTruncated>true</IsTruncated>"
        + "<NextKeyMarker>a+b/1.mp4</NextKeyMarker><NextUploadIdMarker>u2</NextUploadIdMarker>"
        + upload("a+b/1.mp4", "u1") + upload("a+b/1.mp4", "u2") + "</ListMultipartUploadsResult>"));
    S3Client client = client();
    client.listMultipartUploads("bucket", "a b/")
      .eventually(client::close)
      .onComplete(testContext.succeeding(uploads -> testContext.verify(() -> {
        assertEquals(List.of("a b/1.mp4:u1", "a b/1.mp4:u2", "a b/2.mp4:u3"),
          uploads.stream().map(u -> u.getKey() + ":" + u.getUploadId()).collect(Collectors.toList()));
        assertEquals(List.of("/bucket?encoding-type=url&max-uploads=1000&prefix=a%20b%2F&uploads=",
            "/bucket?encoding-type=url&key-marker=a%20b%2F1.mp4&max-uploads=1000&prefix=a%20b%2F&upload-id-marker=u2&uploads="),
          requests.stream().map(r -> r.uri).collect(Collectors.toList()));
        testContext.completeNow();
      })));
  }

  @Test
  void listUploadsWithoutAPrefixListsTheWholeBucket(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "<ListMultipartUploadsResult><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>"));
    S3Client client = client();
    client.listMultipartUploads("bucket", null)
      .compose(none -> client.listMultipartUploads("bucket", ""))
      .eventually(client::close)
      .onComplete(testContext.succeeding(uploads -> testContext.verify(() -> {
        assertEquals(List.<S3MultipartUpload>of(), uploads);
        assertEquals("/bucket?encoding-type=url&max-uploads=1000&uploads=", requests.get(0).uri);
        assertEquals(requests.get(0).uri, requests.get(1).uri);
        testContext.completeNow();
      })));
  }

  @Test
  void listUploadsFailsWhenTheMarkersAreMissingOrDoNotAdvance(VertxTestContext testContext) {
    responder.set(c -> new Reply(200, "<ListMultipartUploadsResult><IsTruncated>true</IsTruncated>" + upload("k", "u")
      + "</ListMultipartUploadsResult>"));
    S3Client client = client();
    client.listMultipartUploads("bucket", null)
      .transform(missing -> {
        testContext.verify(() -> assertEquals("ListMultipartUploads response is truncated but its next markers don't advance",
          missing.cause().getMessage()));
        responder.set(c -> new Reply(200, "<ListMultipartUploadsResult><IsTruncated>true</IsTruncated><NextKeyMarker>k"
          + "</NextKeyMarker><NextUploadIdMarker>u</NextUploadIdMarker>" + upload("k", "u") + "</ListMultipartUploadsResult>"));
        return client.listMultipartUploads("bucket", null);
      })
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("ListMultipartUploads response is truncated but its next markers don't advance", e.getMessage());
        assertEquals(3, requests.size(), "the second listing fails on its second page");
        testContext.completeNow();
      })));
  }

  @Test
  void listUploadsStopsAtTheLimit(VertxTestContext testContext) {
    responder.set(c -> {
      String key = "k" + requests.size();
      return new Reply(200, "<ListMultipartUploadsResult><IsTruncated>true</IsTruncated><NextKeyMarker>" + key
        + "</NextKeyMarker><NextUploadIdMarker>u</NextUploadIdMarker>" + upload(key, "u") + upload(key + "b", "u")
        + "</ListMultipartUploadsResult>");
    });
    S3ClientImpl client = (S3ClientImpl) client();
    client.listMultipartUploads("bucket", "videos/", 2, 3)
      .eventually(client::close)
      .onComplete(testContext.failing(e -> testContext.verify(() -> {
        assertEquals("More than 6 multipart uploads are open under this prefix: list a narrower prefix", e.getMessage());
        assertEquals(3, requests.size());
        testContext.completeNow();
      })));
  }

  // --- checks before any request -------------------------------------------------------------------

  @Test
  void invalidArgumentsFailWithoutAnyRequest(VertxTestContext testContext) {
    S3Client client = client();
    List<Future<?>> calls = List.of(
      client.createMultipartUpload("bucket", "k", new S3PutObjectOptions().putUserMetadata("bad name", "v")),
      client.createMultipartUpload("Bad_Bucket", "k", null),
      client.completeMultipartUpload("bucket", "k", "up-1", List.of(new S3CompletedPart(1, "a"), new S3CompletedPart(1, "b"))),
      client.completeMultipartUpload("bucket", "k", "up-1", List.of()),
      client.completeMultipartUpload("bucket", "k", "", List.of(new S3CompletedPart(1, "a"))),
      client.abortMultipartUpload("bucket", "k", null),
      client.abortMultipartUpload("bucket", "", "up-1"),
      client.listParts("bucket", "k", ""),
      client.listMultipartUploads("bucket", "bad\uD800"));
    Future.join(calls).eventually(client::close).onComplete(ar -> testContext.verify(() -> {
      for (Future<?> call : calls) {
        assertInstanceOf(S3ClientException.class, call.cause(), String.valueOf(call.cause()));
      }
      assertEquals(0, requests.size());
      testContext.completeNow();
    }));
  }

  // --- helpers -------------------------------------------------------------------------------------

  private S3Client client() {
    return S3Client.create(vertx, new S3ClientOptions()
      .setEndpoint("http://localhost:" + http.actualPort())
      .setRegion("test-west-1")
      .setCredentials(new S3Credentials("AKIDEXAMPLE", "secret")));
  }

  private static String partsPage(boolean truncated, Integer next, int... partNumbers) {
    StringBuilder xml = new StringBuilder("<ListPartsResult><IsTruncated>").append(truncated).append("</IsTruncated>");
    if (next != null) {
      xml.append("<NextPartNumberMarker>").append(next).append("</NextPartNumberMarker>");
    }
    for (int n : partNumbers) {
      xml.append("<Part><PartNumber>").append(n).append("</PartNumber><ETag>\"e").append(n).append("\"</ETag><Size>")
        .append(n).append("</Size></Part>");
    }
    return xml.append("</ListPartsResult>").toString();
  }

  private static String upload(String key, String uploadId) {
    return "<Upload><Key>" + key + "</Key><UploadId>" + uploadId + "</UploadId><Initiated>2026-11-22T10:15:30.000Z</Initiated></Upload>";
  }

  private static final class Captured {
    final HttpMethod method;
    final String uri;
    final MultiMap headers;
    final Buffer body;

    Captured(HttpMethod method, String uri, MultiMap headers, Buffer body) {
      this.method = method;
      this.uri = uri;
      this.headers = headers;
      this.body = body;
    }
  }

  private static final class Reply {
    final int status;
    final String body;

    Reply(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }
}
