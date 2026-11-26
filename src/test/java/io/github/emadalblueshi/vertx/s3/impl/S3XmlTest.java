package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Parsing and hardening of {@link S3Xml}. */
class S3XmlTest {

  // --- parsing ---------------------------------------------------------------------------------

  @Test
  void parsesS3DocumentIgnoringNamespaces() {
    XmlElement root = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
      + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
      + "<Name>bucket</Name><KeyCount>2</KeyCount>"
      + "<Contents><Key>a.txt</Key><Size>1</Size></Contents>"
      + "<Contents><Key>b.txt</Key><Size>2</Size></Contents>"
      + "</ListBucketResult>");
    assertEquals("ListBucketResult", root.name());
    assertEquals("bucket", root.childText("Name"));
    List<XmlElement> contents = root.children("Contents");
    assertEquals(2, contents.size());
    assertEquals("b.txt", contents.get(1).childText("Key"));
    assertNull(root.childText("Missing"));
  }

  @Test
  void prefixedNamespacesAreReducedToLocalNames() {
    XmlElement root = parse("<s3:Error xmlns:s3=\"urn:x\"><s3:Code>X</s3:Code></s3:Error>");
    assertEquals("Error", root.name());
    assertEquals("X", root.childText("Code"));
  }

  @Test
  void textIsNotTrimmedAndEntitiesAndCdataAreDecoded() {
    XmlElement root = parse("<r><Key>  lead and trail  </Key><A>a &amp; b &lt;c&gt; &#x41;&#66;</A>"
      + "<C><![CDATA[x <y> & z]]> tail</C></r>");
    assertEquals("  lead and trail  ", root.childText("Key"));
    assertEquals("a & b <c> AB", root.childText("A"));
    assertEquals("x <y> & z tail", root.childText("C"));
  }

  @Test
  void declaredEncodingIsHonoured() {
    byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>café</r>".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals("café", S3Xml.parse(latin1).text());
  }

  @Test
  void commentsAndProcessingInstructionsAreIgnored() {
    XmlElement root = parse("<?xml version=\"1.0\"?><!-- c --><r><?pi x?><K>v<!-- c -->w</K></r>");
    assertEquals("vw", root.childText("K"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   ", "not xml", "<r>", "<r></x>", "<r/><s/>", "<html><body>502 Bad Gateway</body>" })
  void malformedInputIsRejected(String xml) {
    assertThrows(XmlParseException.class, () -> parse(xml));
  }

  @Test
  void depthLimitIsEnforced() {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i <= S3Xml.MAX_DEPTH; i++) {
      deep.append("<e>");
    }
    for (int i = 0; i <= S3Xml.MAX_DEPTH; i++) {
      deep.append("</e>");
    }
    XmlParseException e = assertThrows(XmlParseException.class, () -> parse(deep.toString()));
    assertTrue(e.getMessage().contains("Nesting"), e.getMessage());
  }

  @Test
  void elementCountLimitIsEnforced() {
    StringBuilder many = new StringBuilder("<r>");
    for (int i = 0; i < S3Xml.MAX_ELEMENTS; i++) {
      many.append("<e/>");
    }
    many.append("</r>");
    XmlParseException e = assertThrows(XmlParseException.class, () -> parse(many.toString()));
    assertTrue(e.getMessage().contains("elements"), e.getMessage());
  }

  // --- hardening -------------------------------------------------------------------------------

  @Test
  void bareDoctypeIsRejected() {
    assertThrows(XmlParseException.class, () -> parse("<!DOCTYPE r><r>ok</r>"));
  }

  @Test
  void externalFileEntityIsNotResolved(@TempDir Path dir) throws IOException {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-FILE-CONTENT");
    String xml = "<!DOCTYPE r [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><r>&x;</r>";
    XmlParseException e = assertThrows(XmlParseException.class, () -> parse(xml));
    assertFalse(String.valueOf(e.getMessage()).contains("TOP-SECRET"), e.getMessage());
  }

  @Test
  void entityExpansionIsRejected() {
    String billionLaughs = "<!DOCTYPE r [<!ENTITY a \"aaaaaaaaaa\">"
      + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">"
      + "<!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\">]><r>&d;</r>";
    assertThrows(XmlParseException.class, () -> parse(billionLaughs));
  }

  @Test
  void undeclaredEntityIsRejected() {
    assertThrows(XmlParseException.class, () -> parse("<r>&nbsp;</r>"));
  }

  /**
   * The listener counts a connection <em>before</em> it answers, and a parser that fetches a
   * resource waits for the answer. So once {@code parse} has returned, any fetch it made has
   * already been counted: the check needs no waiting.
   */
  @Test
  void noNetworkAccessForExternalDtdOrEntities() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      AtomicInteger connections = new AtomicInteger();
      Thread listener = new Thread(() -> {
        while (true) {
          try (Socket socket = server.accept()) {
            connections.incrementAndGet();
            socket.getOutputStream().write(
              "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          } catch (IOException e) {
            return; // the server socket was closed
          }
        }
      }, "xxe-listener");
      listener.setDaemon(true);
      listener.start();

      String base = "http://127.0.0.1:" + server.getLocalPort() + "/";
      List<String> documents = List.of(
        "<!DOCTYPE r SYSTEM \"" + base + "external.dtd\"><r/>",
        "<!DOCTYPE r [<!ENTITY x SYSTEM \"" + base + "entity\">]><r>&x;</r>",
        "<!DOCTYPE r [<!ENTITY % p SYSTEM \"" + base + "parameter\"> %p;]><r/>",
        "<r xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"" + base + "xinclude\"/></r>");
      for (String xml : documents) {
        try {
          parse(xml);
        } catch (XmlParseException expected) {
          // rejection is fine; only network access would be a failure
        }
        assertEquals(0, connections.get(), "the parser must never fetch external resources: " + xml);
      }
    }
  }

  private static XmlElement parse(String xml) {
    return S3Xml.parse(xml.getBytes(StandardCharsets.UTF_8));
  }
}
