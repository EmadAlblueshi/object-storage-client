package io.github.emadalblueshi.vertx.s3;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.json.JsonObject;

/**
 * Response header overrides signed into a presigned {@code GET} URL, for
 * {@link S3Presigner#presignGet(String, String, long, java.util.concurrent.TimeUnit, S3PresignGetOptions)}.
 * Each one set replaces the corresponding header of the download response (sent as the signed
 * {@code response-*} query parameters), whatever is stored with the object. Values are non-empty
 * printable US-ASCII; invalid values make presigning throw {@link IllegalArgumentException}.
 */
@DataObject
@JsonGen(publicConverter = false)
public class S3PresignGetOptions {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private String contentType;
  private String contentDisposition;
  private String cacheControl;

  public S3PresignGetOptions() {
  }

  public S3PresignGetOptions(S3PresignGetOptions other) {
    this.contentType = other.contentType;
    this.contentDisposition = other.contentDisposition;
    this.cacheControl = other.cacheControl;
  }

  public S3PresignGetOptions(JsonObject json) {
    S3PresignGetOptionsConverter.fromJson(json, this);
  }

  /** The {@code Content-Type} of the response; {@code null} keeps the stored one. */
  public String getContentType() {
    return contentType;
  }

  public S3PresignGetOptions setContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /** The {@code Content-Disposition} of the response; {@code null} keeps the stored one. */
  public String getContentDisposition() {
    return contentDisposition;
  }

  /**
   * Sets the {@code Content-Disposition} of the response, e.g. to make a browser download the
   * object under a file name. The value is used as is and must be printable US-ASCII: build it
   * with {@link #attachment(String)} or {@link #inline(String)}, which encode any file name.
   */
  public S3PresignGetOptions setContentDisposition(String contentDisposition) {
    this.contentDisposition = contentDisposition;
    return this;
  }

  /** The {@code Cache-Control} of the response; {@code null} keeps the stored one. */
  public String getCacheControl() {
    return cacheControl;
  }

  /** Sets the {@code Cache-Control} of the response, e.g. {@code no-store} for private documents. */
  public S3PresignGetOptions setCacheControl(String cacheControl) {
    this.cacheControl = cacheControl;
    return this;
  }

  /**
   * A {@code Content-Disposition} value that makes a browser save the response as {@code filename}:
   * {@code attachment; filename="..."}, plus {@code filename*=UTF-8''...} (RFC 6266, RFC 8187) when
   * the name isn't plain ASCII, with an ASCII fallback in {@code filename} for old clients.
   *
   * @param filename the file name, any Unicode
   * @throws IllegalArgumentException if {@code filename} is empty or not valid Unicode
   */
  public static String attachment(String filename) {
    return disposition("attachment", filename);
  }

  /** As {@link #attachment(String)}, for a response displayed by the browser ({@code inline}). */
  public static String inline(String filename) {
    return disposition("inline", filename);
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    S3PresignGetOptionsConverter.toJson(this, json);
    return json;
  }

  @Override
  public String toString() {
    return "S3PresignGetOptions{contentType=" + contentType + ", contentDisposition=" + contentDisposition
      + ", cacheControl=" + cacheControl + '}';
  }

  private static String disposition(String type, String filename) {
    Objects.requireNonNull(filename, "filename");
    if (filename.isEmpty()) {
      throw new IllegalArgumentException("The file name must not be empty");
    }
    StringBuilder fallback = new StringBuilder(filename.length());
    for (int i = 0; i < filename.length(); ) {
      int codePoint = filename.codePointAt(i);
      if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
        throw new IllegalArgumentException("The file name is not valid Unicode (unpaired surrogate)");
      }
      boolean plain = codePoint >= 0x20 && codePoint <= 0x7E && codePoint != '"' && codePoint != '\\';
      fallback.append(plain ? (char) codePoint : '_');
      i += Character.charCount(codePoint);
    }
    String value = type + "; filename=\"" + fallback + '"';
    if (fallback.toString().equals(filename)) {
      return value;
    }
    StringBuilder encoded = new StringBuilder();
    for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
      }
    }
    return value + "; filename*=UTF-8''" + encoded;
  }
}
