package io.github.emadalblueshi.vertx.s3.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.vertx.core.buffer.Buffer;

/**
 * Multipart upload checks and request bodies, shared by the public operations and the internal
 * multipart of {@code putObject} from a stream. Check methods return a problem description, or
 * {@code null} when the input is valid; body builders throw {@link IllegalArgumentException}.
 */
final class MultipartRequests {

  /** S3's limits: part numbers 1 to 10,000; each part 5 MiB to 5 GiB, except the last. */
  static final int MAX_PARTS = 10_000;
  static final long MIN_PART_SIZE = 5L * 1024 * 1024;
  static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024;

  /** S3's largest single {@code PUT}: 5 GiB. */
  static final long MAX_PUT_SIZE = 5L * 1024 * 1024 * 1024;

  /** S3's largest object: 5 TiB. */
  static final long MAX_OBJECT_SIZE = 5L * 1024 * 1024 * 1024 * 1024;

  /** Page sizes requested from ListParts and ListMultipartUploads (S3's maximum for both). */
  static final int PAGE_SIZE = 1000;
  /** At most 100,000 open uploads are listed. */
  static final int MAX_UPLOAD_PAGES = 100;

  private MultipartRequests() {
  }

  /**
   * The part size for an upload of {@code size} bytes: the smallest multiple of {@code minimum}
   * that fits it in {@code maxParts} parts (so {@code minimum} up to {@code minimum * maxParts}
   * bytes). For sizes up to {@link #MAX_OBJECT_SIZE} with the 8 MiB minimum and 10,000 parts, at
   * most 552 MiB.
   */
  static int partSizeFor(long size, int minimum, int maxParts) {
    long needed = (size + maxParts - 1) / maxParts;
    long partSize = Math.max(1, (needed + minimum - 1) / minimum) * minimum;
    if (partSize > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Too large for parts of at most 2 GiB: " + size + " bytes");
    }
    return (int) partSize;
  }

  static String uploadIdProblem(String uploadId) {
    if (uploadId == null || uploadId.isEmpty()) {
      return "The upload id is required";
    }
    if (ObjectRequests.utf8Length(uploadId) < 0) {
      return "The upload id is not valid Unicode (unpaired surrogate)";
    }
    return null;
  }

  static String partNumberProblem(int partNumber) {
    return partNumber < 1 || partNumber > MAX_PARTS
      ? "The part number must be 1 to " + MAX_PARTS + ": " + partNumber
      : null;
  }

  static String prefixProblem(String prefix) {
    return prefix != null && ObjectRequests.utf8Length(prefix) < 0
      ? "The prefix is not valid Unicode (unpaired surrogate)"
      : null;
  }

  /**
   * The CompleteMultipartUpload body: the parts in ascending part-number order, as S3 requires.
   *
   * @throws IllegalArgumentException if the list is empty or too long, or a part is invalid or
   *     repeated
   */
  static Buffer completeBody(List<S3CompletedPart> parts) {
    if (parts == null || parts.isEmpty()) {
      throw new IllegalArgumentException("At least one part is required");
    }
    if (parts.size() > MAX_PARTS) {
      throw new IllegalArgumentException("At most " + MAX_PARTS + " parts are allowed: " + parts.size());
    }
    List<S3CompletedPart> sorted = new ArrayList<>(parts.size());
    Set<Integer> numbers = new HashSet<>();
    for (S3CompletedPart part : parts) {
      if (part == null) {
        throw new IllegalArgumentException("A part is null");
      }
      String problem = partNumberProblem(part.getPartNumber());
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }
      if (!numbers.add(part.getPartNumber())) {
        throw new IllegalArgumentException("Part " + part.getPartNumber() + " is listed more than once");
      }
      if (!ObjectRequests.isHeaderValue(part.getEtag())) {
        throw new IllegalArgumentException("Part " + part.getPartNumber() + " needs its ETag (non-empty printable US-ASCII)");
      }
      sorted.add(part);
    }
    sorted.sort(Comparator.comparingInt(S3CompletedPart::getPartNumber));
    XmlWriter xml = new XmlWriter("CompleteMultipartUpload", S3Xml.S3_NAMESPACE);
    for (S3CompletedPart part : sorted) {
      xml.start("Part")
        .element("PartNumber", Integer.toString(part.getPartNumber()))
        .element("ETag", part.getEtag())
        .end();
    }
    return xml.toBuffer();
  }
}
