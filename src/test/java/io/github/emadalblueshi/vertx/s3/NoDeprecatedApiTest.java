package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No deprecated Vert.x or JDK APIs in {@code src/main} (AGENTS.md), checked mechanically: the
 * main sources (and the generated converters) are compiled again by the JDK's {@code javac}, in a
 * child process, with {@code -Xlint:deprecation} and no annotation processors. Every
 * {@code [deprecation]} or {@code [removal]} warning fails the test; other warnings are not its
 * concern. Tests themselves are not checked.
 * <p>
 * A child process because the tests run inside the library's module, which doesn't read
 * {@code java.compiler}; and not {@code -Werror} on the build's own compile, because the docgen
 * annotation processor always emits a warning of its own.
 */
class NoDeprecatedApiTest {

  @Test
  void srcMainUsesNoDeprecatedApis(@TempDir Path work) throws Exception {
    List<Path> sources = new ArrayList<>(javaFiles(Path.of("src", "main", "java")));
    sources.addAll(javaFiles(Path.of("target", "generated-sources", "annotations")));
    // One unnamed module against the dependencies: the module descriptor isn't needed to find deprecations
    sources.removeIf(p -> p.getFileName().toString().equals("module-info.java"));
    assertTrue(sources.size() > 20, "found the main sources: " + sources.size());

    Path argFile = work.resolve("javac.args");
    List<String> args = new ArrayList<>(List.of("--release", "11", "-proc:none", "-Xlint:deprecation", "-implicit:none",
      "-encoding", "UTF-8", "-d", work.resolve("classes").toString(), "-classpath", dependencyPath()));
    sources.forEach(p -> args.add(p.toAbsolutePath().toString()));
    Files.write(argFile, args.stream().map(NoDeprecatedApiTest::quote).collect(Collectors.toList()), StandardCharsets.UTF_8);

    Path javac = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac");
    Process process = new ProcessBuilder(javac.toString(), "-J-Duser.language=en", "@" + argFile)
      .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(2, TimeUnit.MINUTES), "javac timed out");

    assertEquals(0, process.exitValue(), () -> "src/main must compile for the check:\n" + output);
    List<String> deprecations = Arrays.stream(output.split("\\R"))
      .filter(line -> line.contains("warning: [deprecation]") || line.contains("warning: [removal]"))
      .collect(Collectors.toList());
    assertTrue(deprecations.isEmpty(), () -> "Deprecated APIs used in src/main:\n" + String.join("\n", deprecations));
  }

  /** The test JVM's class and module path, minus the project's own output directories. */
  private static String dependencyPath() {
    Path target = Path.of("target").toAbsolutePath();
    return Stream.of(System.getProperty("jdk.module.path", ""), System.getProperty("java.class.path", ""))
      .flatMap(path -> Arrays.stream(path.split(File.pathSeparator)))
      .filter(entry -> !entry.isEmpty() && Files.exists(Path.of(entry)))
      .filter(entry -> !Path.of(entry).toAbsolutePath().startsWith(target))
      .distinct()
      .collect(Collectors.joining(File.pathSeparator));
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  /** Quotes an @argfile entry (paths may contain spaces). */
  private static String quote(String arg) {
    return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
