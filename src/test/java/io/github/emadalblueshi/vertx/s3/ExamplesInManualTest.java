package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Every comment in the docgen snippets reaches the generated manual. Docgen starts a snippet at
 * its method's first statement, so a comment on the first line of an {@code Examples} method is
 * silently dropped (AGENTS.md). The manual is generated when {@code src/main} compiles, so this
 * runs under Maven after docgen; it checks full-line {@code //} comments, the only kind the
 * snippets use.
 */
class ExamplesInManualTest {

  private static final Path EXAMPLES = Path.of("src", "main", "java", "examples", "Examples.java");
  private static final Path MANUAL = Path.of("target", "asciidoc", "java", "index.adoc");

  @Test
  void everyExampleCommentIsInTheGeneratedManual() throws IOException {
    assertTrue(Files.exists(MANUAL), MANUAL + " is missing: docgen generates it when src/main compiles (run through Maven)");
    Map<String, Long> inExamples = comments(EXAMPLES);
    assertFalse(inExamples.isEmpty(), "no comments found in " + EXAMPLES);
    Map<String, Long> inManual = comments(MANUAL);
    List<String> dropped = new ArrayList<>();
    inExamples.forEach((comment, count) -> {
      if (inManual.getOrDefault(comment, 0L) < count) {
        dropped.add(comment);
      }
    });
    assertTrue(dropped.isEmpty(), () -> "Comments missing from the generated manual (does a snippet start with a comment?):\n"
      + String.join("\n", dropped));
  }

  /** The full-line {@code //} comments of {@code file}, trimmed, with their number of occurrences. */
  private static Map<String, Long> comments(Path file) throws IOException {
    return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
      .map(String::trim)
      .filter(line -> line.startsWith("//"))
      .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }
}
