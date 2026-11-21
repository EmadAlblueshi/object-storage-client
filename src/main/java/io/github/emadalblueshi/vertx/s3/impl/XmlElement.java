package io.github.emadalblueshi.vertx.s3.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable element of a parsed S3 XML document. It is a deliberately small tree: S3
 * documents are shallow records and lists, so operations map them with
 * {@link #childText(String)} and {@link #children(String)}.
 * <p>
 * Names are local names, with namespaces ignored. {@link #text()} is the element's own
 * character data, untrimmed (S3 keys may start or end with spaces); for container elements it
 * is usually just whitespace.
 */
final class XmlElement {

  private final String name;
  private final String text;
  private final List<XmlElement> children;

  XmlElement(String name, String text, List<XmlElement> children) {
    this.name = name;
    this.text = text;
    this.children = Collections.unmodifiableList(new ArrayList<>(children));
  }

  String name() {
    return name;
  }

  String text() {
    return text;
  }

  List<XmlElement> children() {
    return children;
  }

  /** The first child named {@code name}, or {@code null}. */
  XmlElement child(String name) {
    for (XmlElement child : children) {
      if (child.name.equals(name)) {
        return child;
      }
    }
    return null;
  }

  /** All children named {@code name}, in document order. */
  List<XmlElement> children(String name) {
    List<XmlElement> matching = new ArrayList<>();
    for (XmlElement child : children) {
      if (child.name.equals(name)) {
        matching.add(child);
      }
    }
    return matching;
  }

  /** The text of the first child named {@code name}, or {@code null} if there is no such child. */
  String childText(String name) {
    XmlElement child = child(name);
    return child == null ? null : child.text;
  }

  @Override
  public String toString() {
    return children.isEmpty() ? "<" + name + ">" + text + "</" + name + ">" : "<" + name + ">" + children + "</" + name + ">";
  }
}
