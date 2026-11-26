package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The control for release gate 4: a buffer leaked on purpose is reported, so "no leak reports"
 * can't pass because the capture sees nothing (e.g. if Netty logged through another backend).
 */
class NettyLeakCheckTest {

  private static final String HINT = "vertx-s3-client: leaked on purpose by NettyLeakCheckTest";

  @Test
  void aBufferLeakedOnPurposeIsReported() throws ReflectiveOperationException {
    NettyLeaks.install();
    Class<?> detector = Class.forName("io.netty.util.ResourceLeakDetector");
    assertEquals("PARANOID", String.valueOf(detector.getMethod("getLevel").invoke(null)));
    // Anything pending is a real leak: fail with it rather than take it for the control's
    assertEquals("", NettyLeaks.collect(), "leaks reported before the control");
    leakOneBuffer();
    String reports = NettyLeaks.collect();
    assertEquals(1, reports.split("LEAK: ", -1).length - 1, reports);
    assertTrue(reports.contains("Hint: " + HINT), reports);
    assertEquals("", NettyLeaks.collect(), "each report is returned once");
  }

  /** Allocates a tracked buffer, marks it with the hint and drops it without releasing it. */
  private static void leakOneBuffer() throws ReflectiveOperationException {
    Object allocator = Class.forName("io.netty.buffer.PooledByteBufAllocator").getField("DEFAULT").get(null);
    Object buffer = Class.forName("io.netty.buffer.ByteBufAllocator").getMethod("directBuffer", int.class)
      .invoke(allocator, 16);
    Class.forName("io.netty.util.ReferenceCounted").getMethod("touch", Object.class).invoke(buffer, HINT);
  }
}
