package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

/** The presign data objects (copy semantics, JSON round trip) and the Content-Disposition helpers. */
class S3PresignDataObjectsTest {

  @Test
  void putOptionsCopyAndJson() {
    S3PresignPutOptions options = new S3PresignPutOptions().setContentType("image/jpeg").setContentMd5("1B2M2Y8AsgTpgAmY7PhCfg==")
      .setContentLength(5_368_709_120L);
    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("contentType", "image/jpeg").put("contentMd5", "1B2M2Y8AsgTpgAmY7PhCfg==")
      .put("contentLength", 5_368_709_120L), json);
    assertEquals(json, new S3PresignPutOptions(json).toJson());
    assertEquals(json, new S3PresignPutOptions(options).toJson());
    assertEquals(5_368_709_120L, new S3PresignPutOptions(json).getContentLength());
    assertNull(new S3PresignPutOptions().getContentType());
    assertNull(new S3PresignPutOptions().getContentMd5());
    assertNull(new S3PresignPutOptions().getContentLength());
    assertEquals(new JsonObject(), new S3PresignPutOptions().toJson(), "an unset length isn't written");
    assertNull(new S3PresignPutOptions(options).setContentLength(null).getContentLength(), "null clears it");
    assertTrue(options.toString().contains("contentLength=5368709120"), options.toString());
  }

  @Test
  void getOptionsCopyAndJson() {
    S3PresignGetOptions options = new S3PresignGetOptions().setContentType("application/pdf")
      .setContentDisposition("attachment; filename=\"a.pdf\"").setCacheControl("no-store");
    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("contentType", "application/pdf").put("contentDisposition", "attachment; filename=\"a.pdf\"")
      .put("cacheControl", "no-store"), json);
    assertEquals(json, new S3PresignGetOptions(json).toJson());
    assertEquals(json, new S3PresignGetOptions(options).toJson());
    assertEquals(new JsonObject(), new S3PresignGetOptions().toJson());
  }

  @Test
  void uploadPartOptionsCopyAndJson() {
    S3PresignUploadPartOptions options = new S3PresignUploadPartOptions().setContentMd5("1B2M2Y8AsgTpgAmY7PhCfg==")
      .setContentLength(8_388_608L);
    JsonObject json = options.toJson();
    assertEquals(new JsonObject().put("contentMd5", "1B2M2Y8AsgTpgAmY7PhCfg==").put("contentLength", 8_388_608L), json);
    assertEquals(json, new S3PresignUploadPartOptions(json).toJson());
    assertEquals(json, new S3PresignUploadPartOptions(options).toJson());
    assertNull(new S3PresignUploadPartOptions().getContentMd5());
    assertNull(new S3PresignUploadPartOptions().getContentLength());
    assertEquals(new JsonObject(), new S3PresignUploadPartOptions().toJson());
    assertTrue(options.toString().contains("contentLength=8388608"), options.toString());
  }

  @Test
  void presignedRequestCopyJsonAndToString() {
    Map<String, String> headers = new HashMap<>(Map.of("Content-Type", "image/jpeg"));
    S3PresignedRequest request = new S3PresignedRequest().setUrl("https://files.example.com/b/k?X-Amz-Signature=abc")
      .setMethod("PUT").setHeaders(headers);
    headers.put("later", "ignored");
    assertEquals(Map.of("Content-Type", "image/jpeg"), request.getHeaders(), "the map is copied");
    assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().put("x", "y"));

    JsonObject json = request.toJson();
    assertEquals(new JsonObject().put("url", "https://files.example.com/b/k?X-Amz-Signature=abc").put("method", "PUT")
      .put("headers", new JsonObject().put("Content-Type", "image/jpeg")), json);
    assertEquals(json, new S3PresignedRequest(json).toJson());
    assertEquals(json, new S3PresignedRequest(request).toJson());
    assertEquals(Map.of(), new S3PresignedRequest().getHeaders());

    String text = request.toString();
    assertTrue(text.contains("PUT") && text.contains("image/jpeg"), text);
    assertFalse(text.contains("files.example.com") || text.contains("Signature"), "never the URL: " + text);
  }

  @Test
  void asciiFileNamesNeedNoEncoding() {
    assertEquals("attachment; filename=\"report 2026.pdf\"", S3PresignGetOptions.attachment("report 2026.pdf"));
    assertEquals("inline; filename=\"photo.jpg\"", S3PresignGetOptions.inline("photo.jpg"));
  }

  @Test
  void nonAsciiFileNamesAreEncodedWithAnAsciiFallback() {
    assertEquals("attachment; filename=\"______ 7.pdf\"; filename*=UTF-8''%D9%81%D8%A7%D8%AA%D9%88%D8%B1%D8%A9%207.pdf",
      S3PresignGetOptions.attachment("فاتورة 7.pdf"));
    assertEquals("inline; filename=\"_.png\"; filename*=UTF-8''%F0%9F%98%80.png", S3PresignGetOptions.inline("😀.png"));
    // U+1D800: a valid code point whose low 16 bits fall in the surrogate range
    assertEquals("attachment; filename=\"_\"; filename*=UTF-8''%F0%9D%A0%80", S3PresignGetOptions.attachment("𝠀"));
  }

  @Test
  void quotesBackslashesAndControlCharactersOnlyAppearEncoded() {
    assertEquals("attachment; filename=\"a_b_c_.txt\"; filename*=UTF-8''a%22b%5Cc%0A.txt",
      S3PresignGetOptions.attachment("a\"b\\c\n.txt"));
    // Separators are safe inside the quoted string: no filename* needed
    assertEquals("attachment; filename=\"x;y=z, (1).txt\"", S3PresignGetOptions.attachment("x;y=z, (1).txt"));
  }

  @Test
  void invalidFileNamesAreRejected() {
    assertThrows(NullPointerException.class, () -> S3PresignGetOptions.attachment(null));
    assertThrows(IllegalArgumentException.class, () -> S3PresignGetOptions.attachment(""));
    assertThrows(IllegalArgumentException.class, () -> S3PresignGetOptions.inline("bad\uD800.txt"));
  }
}
