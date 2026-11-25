package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Vertx;

/**
 * Enforces the package layout rules in AGENTS.md on the compiled main classes:
 * <ul>
 *   <li>Every class is in the API package, the {@code impl} package (or below it), or the docgen
 *   {@code examples} package.</li>
 *   <li>The API package holds only public top-level types with {@code S3}-prefixed names that
 *   don't clash with JDK or Vert.x public type names.</li>
 *   <li>No public API signature (supertype, method, constructor, field, type bound; generics
 *   included) mentions an impl type.</li>
 *   <li>Impl classes are package-private unless listed in {@link #PUBLIC_IMPL_ALLOWLIST}.</li>
 *   <li>{@code module-info} exports only the API package, unqualified, and opens nothing.</li>
 *   <li>Every public API interface is {@code @VertxGen}.</li>
 * </ul>
 */
class PackagePolicyTest {

  private static final String API = "io.github.emadalblueshi.vertx.s3";
  private static final String IMPL = API + ".impl";
  private static final String EXAMPLES = "examples";

  /**
   * Impl classes that must be public, by binary name (e.g. a client implementation the API
   * factory instantiates across packages). Keep it minimal; every entry needs a reason.
   */
  private static final Set<String> PUBLIC_IMPL_ALLOWLIST = Set.of(
    // S3Client.create (API package) instantiates it across packages.
    IMPL + ".S3ClientImpl",
    // S3Presigner.create (API package) instantiates it across packages (owner decision D2, 2026-11-21).
    IMPL + ".S3PresignerImpl");

  /** Main sources of the API and impl packages, relative to the Maven base directory. */
  private static final Path MAIN_SOURCES = Path.of("src", "main", "java", "io", "github", "emadalblueshi", "vertx", "s3");

  /** The one impl class allowed to use {@code io.vertx.core.internal} (Vert.x rule 3 and the internal API policy). */
  private static final String CONTEXT_HELPER = "impl/ContextSupport.java";

  /**
   * Every Vert.x internal type {@link #CONTEXT_HELPER} uses, as the internal API policy in AGENTS.md
   * allows them. Another internal type needs the owner's approval first; a listed type that is no
   * longer used is removed from the list.
   */
  private static final Set<String> ALLOWED_INTERNAL_TYPES = Set.of(
    "io.vertx.core.internal.Closeable",
    "io.vertx.core.internal.CloseableResource",
    "io.vertx.core.internal.ContextInternal",
    "io.vertx.core.internal.PromiseInternal",
    "io.vertx.core.internal.VertxInternal",
    // Vert.x's logger, for the warning at client creation (owner decision, 2026-11-25)
    "io.vertx.core.internal.logging.LoggerFactory");

  /** A reference to a type in {@code io.vertx.core.internal} or a subpackage, or a wildcard import of one. */
  private static final Pattern INTERNAL_TYPE = Pattern.compile(
    "\\bio\\.vertx\\.core\\.internal(?:\\.[a-z][a-z0-9_]*)*\\.(?:[A-Z][A-Za-z0-9_]*|\\*)");

  /** Context-less Future factories that must never build a Future the public API returns (rule 3). */
  private static final Pattern CONTEXT_LESS_FUTURES = Pattern.compile(
    "\\bPromise\\s*\\.\\s*promise\\s*\\(|\\bFuture\\s*\\.\\s*(succeededFuture|failedFuture|all|any|join)\\s*\\("
      + "|\\bimport\\s+static\\s+io\\.vertx\\.core\\.(Future|Promise)\\.");

  private static final Pattern INTERNAL_API = Pattern.compile(
    "\\bio\\.vertx\\.core\\.internal\\b|\\b(ContextInternal|PromiseInternal|VertxInternal)\\b");

  private static Path classesRoot;
  private static List<Class<?>> mainClasses;

  @BeforeAll
  static void loadMainClasses() throws IOException, URISyntaxException {
    classesRoot = Path.of(S3Client.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(Files.isDirectory(classesRoot), "expected compiled classes directory, got " + classesRoot);
    List<Class<?>> classes = new ArrayList<>();
    try (Stream<Path> files = Files.walk(classesRoot)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
        String relative = classesRoot.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        if (relative.equals("module-info.class") || relative.endsWith("/package-info.class")) {
          continue;
        }
        String binaryName = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
        try {
          classes.add(Class.forName(binaryName, false, S3Client.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException("cannot load " + binaryName, e);
        }
      }
    }
    mainClasses = classes;
    assertFalse(mainClasses.isEmpty(), "no main classes found under " + classesRoot);
  }

  @Test
  void everyClassIsInTheApiImplOrExamplesPackage() {
    List<String> misplaced = mainClasses.stream()
      .map(Class::getName)
      .filter(n -> !packageOf(n).equals(API) && !isImpl(n) && !packageOf(n).equals(EXAMPLES))
      .collect(Collectors.toList());
    assertTrue(misplaced.isEmpty(), () -> "classes outside the API/impl/examples packages: " + misplaced);
  }

  @Test
  void apiPackageHoldsOnlyPublicS3PrefixedTopLevelTypes() {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : apiTopLevelTypes(false)) {
      if (!Modifier.isPublic(type.getModifiers())) {
        violations.add(type.getName() + " is not public (move it to impl)");
      } else if (!type.getSimpleName().startsWith("S3")) {
        violations.add(type.getName() + " is not S3-prefixed");
      }
    }
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  /**
   * Every API interface is {@code @VertxGen}, as in Vert.x's own clients, so the codegen processor
   * checks at compile time that its signatures only use codegen-legal types.
   */
  @Test
  void apiInterfacesAreVertxGen() {
    List<String> missing = apiTopLevelTypes(true).stream()
      .filter(Class::isInterface)
      .filter(c -> !c.isAnnotationPresent(VertxGen.class))
      .map(Class::getSimpleName)
      .collect(Collectors.toList());
    assertTrue(missing.isEmpty(), () -> "API interfaces without @VertxGen: " + missing);
  }

  /**
   * Vert.x codegen generates a JSON converter next to each data object: {@code XConverter}, a
   * public class with only package-private static methods (even with
   * {@code publicConverter = false}). It's allowed in the API package only in exactly that shape.
   */
  @Test
  void generatedConvertersExposeNothing() {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : apiTopLevelTypes(true)) {
      if (!type.getSimpleName().endsWith("Converter")) {
        continue;
      }
      String dataObjectName = type.getName().substring(0, type.getName().length() - "Converter".length());
      Class<?> dataObject = mainClasses.stream().filter(c -> c.getName().equals(dataObjectName)).findFirst().orElse(null);
      if (dataObject == null || !Modifier.isPublic(dataObject.getModifiers())) {
        violations.add(type.getName() + " has no matching public data object " + dataObjectName);
        continue;
      }
      try {
        dataObject.getConstructor(io.vertx.core.json.JsonObject.class);
        dataObject.getMethod("toJson");
      } catch (NoSuchMethodException e) {
        violations.add(dataObjectName + " is not a data object (needs a public JsonObject constructor and toJson())");
      }
      for (Method method : type.getDeclaredMethods()) {
        if (exposed(method.getModifiers())) {
          violations.add(type.getName() + " exposes method " + method.getName());
        }
      }
      for (Field field : type.getDeclaredFields()) {
        if (exposed(field.getModifiers())) {
          violations.add(type.getName() + " exposes field " + field.getName());
        }
      }
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        if (constructor.getParameterCount() != 0) {
          violations.add(type.getName() + " declares a constructor with parameters");
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  /**
   * Rule-3 guard. No main source in the API or impl packages builds a Future from a context-less
   * factory ({@code Promise.promise()}, {@code Future.succeededFuture/failedFuture},
   * {@code Future.all/any/join}). Only {@code ContextSupport} uses Vert.x internal APIs, and only
   * the types in {@link #ALLOWED_INTERNAL_TYPES}, each of which it uses. Comments and string/char
   * literals are ignored. Review covers what a source scan can't.
   */
  @Test
  void rule3GuardContextBoundFuturesAndInternalApiConfinement() throws IOException {
    assertTrue(Files.isDirectory(MAIN_SOURCES), "run from the Maven base directory; missing " + MAIN_SOURCES);
    List<String> violations = new ArrayList<>();
    boolean helperSeen = false;
    Set<String> internalTypesUsed = new TreeSet<>();
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
        String relative = MAIN_SOURCES.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        String code = stripCommentsAndLiterals(Files.readString(file));
        Matcher contextLess = CONTEXT_LESS_FUTURES.matcher(code);
        while (contextLess.find()) {
          violations.add(relative + ": context-less Future factory '" + contextLess.group().trim() + "'");
        }
        boolean usesInternal = INTERNAL_API.matcher(code).find();
        if (relative.equals(CONTEXT_HELPER)) {
          helperSeen = usesInternal;
          Matcher internalType = INTERNAL_TYPE.matcher(code);
          while (internalType.find()) {
            internalTypesUsed.add(internalType.group());
          }
        } else if (usesInternal) {
          violations.add(relative + ": uses Vert.x internal API (only " + CONTEXT_HELPER + " may)");
        }
      }
    }
    assertTrue(helperSeen, CONTEXT_HELPER + " is expected to exist and hold the internal API usage");
    for (String type : internalTypesUsed) {
      if (!ALLOWED_INTERNAL_TYPES.contains(type)) {
        violations.add(CONTEXT_HELPER + ": uses " + type + ", which isn't an allowed internal type (ask the owner first)");
      }
    }
    for (String type : ALLOWED_INTERNAL_TYPES) {
      if (!internalTypesUsed.contains(type)) {
        violations.add(CONTEXT_HELPER + ": no longer uses " + type + "; remove it from ALLOWED_INTERNAL_TYPES");
      }
    }
    assertTrue(violations.isEmpty(), () -> "Rule-3 guard violations:\n" + String.join("\n", violations));
  }

  /** Removes char/string literals, block comments and line comments (in that order), keeping line structure loosely. */
  static String stripCommentsAndLiterals(String source) {
    String s = source.replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
    s = s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    s = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(s).replaceAll(" ");
    return s.replaceAll("//[^\n]*", "");
  }

  @Test
  void apiSignaturesNeverMentionImplTypes() {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : exposedApiTypes()) {
      check(violations, type, "extends", type.getGenericSuperclass());
      for (Type itf : type.getGenericInterfaces()) {
        check(violations, type, "implements", itf);
      }
      for (TypeVariable<?> variable : type.getTypeParameters()) {
        for (Type bound : variable.getBounds()) {
          check(violations, type, "type bound", bound);
        }
      }
      for (Field field : type.getDeclaredFields()) {
        if (exposed(field.getModifiers()) && !field.isSynthetic()) {
          check(violations, type, "field " + field.getName(), field.getGenericType());
        }
      }
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        if (exposed(constructor.getModifiers()) && !constructor.isSynthetic()) {
          checkExecutable(violations, type, "constructor", constructor);
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        if (exposed(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
          check(violations, type, method.getName() + "() return", method.getGenericReturnType());
          checkExecutable(violations, type, method.getName() + "()", method);
        }
      }
    }
    assertTrue(violations.isEmpty(), () -> "API signatures mention impl types:\n" + String.join("\n", violations));
  }

  @Test
  void implClassesArePackagePrivateUnlessAllowlisted() {
    Set<String> publicImpl = new TreeSet<>();
    for (Class<?> type : mainClasses) {
      if (isImpl(type.getName()) && !type.isAnonymousClass() && !type.isLocalClass() && !type.isSynthetic()
        && (Modifier.isPublic(type.getModifiers()) || Modifier.isProtected(type.getModifiers()))) {
        publicImpl.add(type.getName());
      }
    }
    Set<String> unexpected = new TreeSet<>(publicImpl);
    unexpected.removeAll(PUBLIC_IMPL_ALLOWLIST);
    assertTrue(unexpected.isEmpty(), () -> "impl classes must be package-private (or allowlisted): " + unexpected);
    Set<String> stale = new TreeSet<>(PUBLIC_IMPL_ALLOWLIST);
    stale.removeAll(publicImpl);
    assertTrue(stale.isEmpty(), () -> "allowlist entries that are not public impl classes (remove them): " + stale);
  }

  @Test
  void moduleExportsOnlyTheApiPackage() throws IOException {
    ModuleDescriptor descriptor;
    try (InputStream in = Files.newInputStream(classesRoot.resolve("module-info.class"))) {
      descriptor = ModuleDescriptor.read(in);
    }
    assertEquals("io.github.emadalblueshi.vertx.s3", descriptor.name());
    assertFalse(descriptor.isOpen(), "module must not be open");
    assertTrue(descriptor.opens().isEmpty(), () -> "module must not open packages: " + descriptor.opens());
    assertEquals(Set.of(API), descriptor.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()));
    assertTrue(descriptor.exports().stream().noneMatch(ModuleDescriptor.Exports::isQualified), "exports must be unqualified");
  }

  @Test
  void apiTypeNamesDoNotClashWithJdkOrVertxPublicTypes() throws IOException, URISyntaxException {
    Set<String> apiNames = apiTopLevelTypes(true).stream().map(Class::getSimpleName).collect(Collectors.toSet());
    Set<String> taken = new HashSet<>(jdkPublicTypeNames());
    taken.addAll(vertxPublicTypeNames());
    Set<String> clashes = new TreeSet<>(apiNames);
    clashes.retainAll(taken);
    assertTrue(clashes.isEmpty(), () -> "API type names clash with JDK/Vert.x types: " + clashes);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static List<Class<?>> apiTopLevelTypes(boolean publicOnly) {
    return mainClasses.stream()
      .filter(c -> packageOf(c.getName()).equals(API) && c.getEnclosingClass() == null)
      .filter(c -> !publicOnly || Modifier.isPublic(c.getModifiers()))
      .collect(Collectors.toList());
  }

  /** Public API types plus their public/protected member types, recursively. */
  private static List<Class<?>> exposedApiTypes() {
    List<Class<?>> result = new ArrayList<>();
    List<Class<?>> pending = new ArrayList<>(apiTopLevelTypes(true));
    while (!pending.isEmpty()) {
      Class<?> type = pending.remove(pending.size() - 1);
      result.add(type);
      for (Class<?> member : type.getDeclaredClasses()) {
        if (exposed(member.getModifiers())) {
          pending.add(member);
        }
      }
    }
    return result;
  }

  private static boolean exposed(int modifiers) {
    return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
  }

  private static void checkExecutable(List<String> violations, Class<?> owner, String what, Executable executable) {
    for (Type parameter : executable.getGenericParameterTypes()) {
      check(violations, owner, what + " parameter", parameter);
    }
    for (Type exception : executable.getGenericExceptionTypes()) {
      check(violations, owner, what + " throws", exception);
    }
    for (TypeVariable<?> variable : executable.getTypeParameters()) {
      for (Type bound : variable.getBounds()) {
        check(violations, owner, what + " type bound", bound);
      }
    }
  }

  private static void check(List<String> violations, Class<?> owner, String where, Type type) {
    Set<Class<?>> referenced = new HashSet<>();
    collect(type, referenced, new HashSet<>());
    for (Class<?> c : referenced) {
      if (isImpl(c.getName())) {
        violations.add(owner.getName() + " " + where + " -> " + c.getName());
      }
    }
  }

  private static void collect(Type type, Set<Class<?>> out, Set<Type> seen) {
    if (type == null || !seen.add(type)) {
      return;
    }
    if (type instanceof Class) {
      Class<?> c = (Class<?>) type;
      out.add(c.isArray() ? c.getComponentType() : c);
    } else if (type instanceof ParameterizedType) {
      ParameterizedType p = (ParameterizedType) type;
      collect(p.getRawType(), out, seen);
      collect(p.getOwnerType(), out, seen);
      for (Type argument : p.getActualTypeArguments()) {
        collect(argument, out, seen);
      }
    } else if (type instanceof WildcardType) {
      WildcardType w = (WildcardType) type;
      for (Type bound : w.getUpperBounds()) {
        collect(bound, out, seen);
      }
      for (Type bound : w.getLowerBounds()) {
        collect(bound, out, seen);
      }
    } else if (type instanceof GenericArrayType) {
      collect(((GenericArrayType) type).getGenericComponentType(), out, seen);
    } else if (type instanceof TypeVariable) {
      for (Type bound : ((TypeVariable<?>) type).getBounds()) {
        collect(bound, out, seen);
      }
    }
  }

  private static boolean isImpl(String className) {
    return className.startsWith(IMPL + ".");
  }

  private static String packageOf(String className) {
    int dot = className.lastIndexOf('.');
    return dot < 0 ? "" : className.substring(0, dot);
  }

  /** Simple names of top-level types in packages the JDK's system modules export unqualified. */
  private static Set<String> jdkPublicTypeNames() throws IOException {
    Set<String> names = new HashSet<>();
    for (ModuleReference module : ModuleFinder.ofSystem().findAll()) {
      Set<String> exported = module.descriptor().exports().stream()
        .filter(e -> !e.isQualified()).map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
      if (exported.isEmpty()) {
        continue;
      }
      try (ModuleReader reader = module.open(); Stream<String> entries = reader.list()) {
        entries.filter(e -> e.endsWith(".class") && !e.contains("$") && !e.endsWith("-info.class"))
          .forEach(e -> {
            String binary = e.substring(0, e.length() - ".class".length()).replace('/', '.');
            if (exported.contains(packageOf(binary))) {
              names.add(binary.substring(binary.lastIndexOf('.') + 1));
            }
          });
      }
    }
    assertTrue(names.contains("String") && names.contains("Optional"), "JDK type scan looks broken");
    return names;
  }

  /** Simple names of top-level Vert.x core types outside {@code impl}/{@code internal} packages. */
  private static Set<String> vertxPublicTypeNames() throws IOException, URISyntaxException {
    Path jar = Path.of(Vertx.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Set<String> names = new HashSet<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      zip.stream().map(ZipEntry::getName)
        .filter(n -> n.startsWith("io/vertx/") && n.endsWith(".class") && !n.contains("$")
          && !n.contains("/impl/") && !n.contains("/internal/") && !n.endsWith("-info.class"))
        .forEach(n -> names.add(n.substring(n.lastIndexOf('/') + 1, n.length() - ".class".length())));
    }
    assertTrue(names.contains("Vertx") && names.contains("Future"), "Vert.x type scan looks broken");
    return names;
  }
}
