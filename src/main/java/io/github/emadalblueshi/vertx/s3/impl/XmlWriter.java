package io.github.emadalblueshi.vertx.s3.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.regex.Pattern;

import io.vertx.core.buffer.Buffer;

/**
 * Writes S3 XML request bodies (UTF-8, XML 1.0). Every text and attribute value is escaped; XML
 * is never built by string concatenation elsewhere.
 * <ul>
 *   <li>{@code &}, {@code <} and {@code >} are always escaped, plus {@code "} and {@code '} in
 *   attributes.</li>
 *   <li>Carriage returns are written as {@code &#xD;} (attributes also escape tab and line feed),
 *   so the parser's line-end normalisation can't alter values such as S3 keys.</li>
 *   <li>Characters that XML 1.0 cannot represent at all (most C0 controls, unpaired surrogates,
 *   U+FFFE/U+FFFF) are rejected with {@link IllegalArgumentException}. A request with such a key
 *   can't be expressed in XML.</li>
 * </ul>
 * Single use: build, then call {@link #toBuffer()} once.
 */
final class XmlWriter {

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

  private final StringBuilder out = new StringBuilder(256);
  private final Deque<String> open = new ArrayDeque<>();
  private boolean finished;

  /** Starts a document with root element {@code root}, declaring {@code namespace} as default (if not {@code null}). */
  XmlWriter(String root, String namespace) {
    out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append('<').append(checkName(root));
    if (namespace != null) {
      out.append(" xmlns=\"").append(escape(namespace, true)).append('"');
    }
    out.append('>');
    open.push(root);
  }

  /** Opens a child element. */
  XmlWriter start(String name) {
    checkOpen();
    out.append('<').append(checkName(name)).append('>');
    open.push(name);
    return this;
  }

  /** Writes {@code <name>text</name>} with {@code text} escaped. */
  XmlWriter element(String name, String text) {
    checkOpen();
    checkName(name);
    out.append('<').append(name).append('>')
      .append(escape(Objects.requireNonNull(text, "text"), false))
      .append("</").append(name).append('>');
    return this;
  }

  /** Closes the innermost child element (the root is closed by {@link #toBuffer()}). */
  XmlWriter end() {
    checkOpen();
    if (open.size() == 1) {
      throw new IllegalStateException("No open child element; the root is closed by toBuffer()");
    }
    out.append("</").append(open.pop()).append('>');
    return this;
  }

  /** Closes the root element and returns the UTF-8 document. */
  Buffer toBuffer() {
    checkOpen();
    if (open.size() != 1) {
      throw new IllegalStateException("Unclosed elements: " + open);
    }
    out.append("</").append(open.pop()).append('>');
    finished = true;
    return Buffer.buffer(out.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** Escapes {@code value} for XML text ({@code attribute == false}) or a double-quoted attribute. */
  static String escape(String value, boolean attribute) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); ) {
      int cp = value.codePointAt(i);
      int width = Character.charCount(cp);
      if (!isXmlChar(cp)) {
        throw new IllegalArgumentException(String.format(
          "Character U+%04X at index %d cannot be represented in XML 1.0", cp, i));
      }
      switch (cp) {
        case '&':
          escaped.append("&amp;");
          break;
        case '<':
          escaped.append("&lt;");
          break;
        case '>':
          escaped.append("&gt;");
          break;
        case '\r':
          escaped.append("&#xD;");
          break;
        case '"':
          escaped.append(attribute ? "&quot;" : "\"");
          break;
        case '\'':
          escaped.append(attribute ? "&apos;" : "'");
          break;
        case '\n':
          escaped.append(attribute ? "&#xA;" : "\n");
          break;
        case '\t':
          escaped.append(attribute ? "&#x9;" : "\t");
          break;
        default:
          escaped.appendCodePoint(cp);
      }
      i += width;
    }
    return escaped.toString();
  }

  /** XML 1.0 {@code Char}. Unpaired surrogates arrive as surrogate code points and are excluded. */
  private static boolean isXmlChar(int cp) {
    return cp == 0x9 || cp == 0xA || cp == 0xD
      || (cp >= 0x20 && cp <= 0xD7FF)
      || (cp >= 0xE000 && cp <= 0xFFFD)
      || (cp >= 0x10000 && cp <= 0x10FFFF);
  }

  private static String checkName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid XML element name: " + name);
    }
    return name;
  }

  private void checkOpen() {
    if (finished) {
      throw new IllegalStateException("Document already finished");
    }
  }
}
