package io.github.emadalblueshi.vertx.s3.impl;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.vertx.core.buffer.Buffer;

/**
 * Hardened parsing of S3 XML responses with the JDK's built-in StAX implementation.
 * <ul>
 *   <li>Always the JDK implementation ({@link XMLInputFactory#newDefaultFactory()}), never
 *   whatever StAX provider happens to be on the application's class path (e.g. Woodstox), so the
 *   hardening below behaves the same everywhere.</li>
 *   <li>DTDs and external entities are disabled, external DTD and schema access is blocked, and
 *   any DTD or entity-reference event is rejected outright (defence in depth: with DTDs disabled
 *   the JDK still reports a bare {@code <!DOCTYPE>} and keeps parsing).</li>
 *   <li>Depth and element-count limits bound memory for hostile responses. The transport also
 *   bounds the body size.</li>
 *   <li>One factory per parse: the JDK factory is not safe to share between threads (it writes an
 *   unsynchronised reader field), and thread-locals don't help on virtual threads.</li>
 * </ul>
 */
final class S3Xml {

  /** The S3 XML namespace, used by request bodies. */
  static final String S3_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";

  static final int MAX_DEPTH = 32;
  static final int MAX_ELEMENTS = 200_000;

  private static final Set<String> ERROR_FIELDS = Set.of("Code", "Message", "Resource", "RequestId", "HostId");

  private S3Xml() {
  }

  static XmlElement parse(Buffer xml) {
    return parse(xml.getBytes());
  }

  /**
   * Parses a complete document into an element tree.
   *
   * @throws XmlParseException if the document is malformed, contains a DTD or entity references,
   *     or exceeds the limits
   */
  static XmlElement parse(byte[] xml) {
    XMLStreamReader reader = null;
    try {
      reader = newFactory().createXMLStreamReader(new ByteArrayInputStream(xml));
      Deque<ElementBuilder> open = new ArrayDeque<>();
      XmlElement root = null;
      int elements = 0;
      while (reader.hasNext()) {
        switch (reader.next()) {
          case XMLStreamConstants.DTD:
            throw new XmlParseException("DTDs are not allowed");
          case XMLStreamConstants.ENTITY_REFERENCE:
            throw new XmlParseException("Entity references are not allowed");
          case XMLStreamConstants.START_ELEMENT:
            if (root != null) {
              throw new XmlParseException("Content after the root element");
            }
            if (open.size() >= MAX_DEPTH) {
              throw new XmlParseException("Nesting deeper than " + MAX_DEPTH);
            }
            if (++elements > MAX_ELEMENTS) {
              throw new XmlParseException("More than " + MAX_ELEMENTS + " elements");
            }
            open.push(new ElementBuilder(reader.getLocalName()));
            break;
          case XMLStreamConstants.CHARACTERS:
          case XMLStreamConstants.CDATA:
          case XMLStreamConstants.SPACE:
            if (!open.isEmpty()) {
              open.peek().text.append(reader.getText());
            }
            break;
          case XMLStreamConstants.END_ELEMENT:
            XmlElement element = open.pop().build();
            if (open.isEmpty()) {
              root = element;
            } else {
              open.peek().children.add(element);
            }
            break;
          default:
            break; // comments, processing instructions, document start/end
        }
      }
      if (root == null) {
        throw new XmlParseException("No root element");
      }
      return root;
    } catch (XMLStreamException e) {
      throw new XmlParseException("Malformed XML: " + e.getMessage(), e);
    } finally {
      close(reader);
    }
  }

  /**
   * Parses an S3 {@code <Error>} document.
   *
   * @return the error document, or {@code null} if the body is empty, not well-formed or
   *     hostile XML (e.g. a load balancer's HTML page), or has a different root element. The
   *     caller then falls back to the HTTP status.
   */
  static ErrorDocument parseError(Buffer body) {
    return body == null ? null : parseError(body.getBytes());
  }

  static ErrorDocument parseError(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    XmlElement root;
    try {
      root = parse(body);
    } catch (XmlParseException e) {
      return null;
    }
    if (!"Error".equals(root.name())) {
      return null;
    }
    Map<String, String> details = new LinkedHashMap<>();
    for (XmlElement child : root.children()) {
      if (child.children().isEmpty() && !ERROR_FIELDS.contains(child.name())) {
        details.putIfAbsent(child.name(), child.text());
      }
    }
    return new ErrorDocument(root.childText("Code"), root.childText("Message"), root.childText("Resource"),
      root.childText("RequestId"), root.childText("HostId"), details);
  }

  private static XMLInputFactory newFactory() {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_VALIDATING, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory;
  }

  private static void close(XMLStreamReader reader) {
    if (reader != null) {
      try {
        reader.close();
      } catch (XMLStreamException ignored) {
        // nothing to release beyond the in-memory input
      }
    }
  }

  private static final class ElementBuilder {
    final String name;
    final StringBuilder text = new StringBuilder();
    final List<XmlElement> children = new ArrayList<>();

    ElementBuilder(String name) {
      this.name = name;
    }

    XmlElement build() {
      return new XmlElement(name, text.toString(), children);
    }
  }
}
