package io.github.emadalblueshi.vertx.s3;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Release gate 4: a test class fails when Netty reports a leaked buffer, checked once the class's
 * Vert.x instances are closed, and the run fails when one is reported later, checked at its end.
 * Every test class that runs Vert.x registers it, before {@code VertxExtension}
 * ({@code PackagePolicyTest} checks it). Leaks are reported when a buffer is garbage-collected, so
 * one may surface in a later class's check; Netty's report shows where the buffer was used.
 */
public final class NettyLeakCheck implements BeforeAllCallback {

  private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(NettyLeakCheck.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    NettyLeaks.install();
    // A store closes its values in reverse insertion order: this check closes after the Vertx
    // instance that VertxExtension stores later, for the class's @BeforeAll methods
    String testClass = context.getRequiredTestClass().getSimpleName();
    context.getStore(NAMESPACE).put("class", (AutoCloseable) () -> check(testClass));
    context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent("run",
      key -> (AutoCloseable) () -> check("the last test class"), AutoCloseable.class);
  }

  private static void check(String after) {
    String leaks = NettyLeaks.collect();
    if (!leaks.isEmpty()) {
      throw new AssertionError("Netty reported leaked buffers, checked after " + after + ":\n" + leaks);
    }
  }
}
