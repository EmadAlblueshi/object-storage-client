package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.buffer.Buffer;

/** Escaping and structure of {@link XmlWriter}, with round trips through {@link S3Xml}. */
class XmlWriterTest {

  @Test
  void writesDeclarationNamespaceAndNestedElements() {
    Buffer xml = new XmlWriter("Delete", S3Xml.S3_NAMESPACE)
      .element("Quiet", "true")
      .start("Object").element("Key", "a.txt").end()
      .toBuffer();
    assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
      + "<Delete xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Quiet>true</Quiet>"
      + "<Object><Key>a.txt</Key></Object></Delete>", xml.toString());
  }

  @Test
  void escapesMarkupInText() {
    assertEquals("a &amp; b &lt;c&gt; \"q\" 'a'", XmlWriter.escape("a & b <c> \"q\" 'a'", false));
    assertEquals("x&#xD;y\nz\tw", XmlWriter.escape("x\ry\nz\tw", false));
  }

  @Test
  void escapesMoreInAttributes() {
    assertEquals("&quot;&apos;&amp;&lt;&gt;&#xD;&#xA;&#x9;", XmlWriter.escape("\"'&<>\r\n\t", true));
  }

  @ParameterizedTest
  @ValueSource(strings = { "\u0000", "a\u0001b", "\u001f", "￾", "￿", "\ud800", "x\udc00y" })
  void rejectsCharactersXmlCannotRepresent(String value) {
    assertThrows(IllegalArgumentException.class, () -> XmlWriter.escape(value, false));
  }

  @Test
  void trickyKeysRoundTripThroughTheParser() {
    List<String> keys = List.of(
      "  spaces around  ", "a&b<c>d\"e'f", "line\r\nbreak\rcr", "tab\there", "emoji-🚀/üß",
      "]]>cdata-end", "&amp; literal");
    for (String key : keys) {
      Buffer xml = new XmlWriter("Delete", S3Xml.S3_NAMESPACE).start("Object").element("Key", key).end().toBuffer();
      assertEquals(key, S3Xml.parse(xml).child("Object").childText("Key"), "round trip of " + key);
    }
  }

  @Test
  void structureMisuseIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new XmlWriter("bad name", null));
    assertThrows(IllegalArgumentException.class, () -> new XmlWriter("R", null).element("1x", "v"));
    assertThrows(IllegalStateException.class, () -> new XmlWriter("R", null).end());
    assertThrows(IllegalStateException.class, () -> new XmlWriter("R", null).start("A").toBuffer());
    XmlWriter writer = new XmlWriter("R", null);
    writer.toBuffer();
    assertThrows(IllegalStateException.class, writer::toBuffer);
    assertThrows(NullPointerException.class, () -> new XmlWriter("R", null).element("A", null));
  }

  @Test
  void rootWithoutNamespace() {
    assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><R><A>1</A></R>",
      new XmlWriter("R", null).element("A", "1").toBuffer().toString());
  }
}
