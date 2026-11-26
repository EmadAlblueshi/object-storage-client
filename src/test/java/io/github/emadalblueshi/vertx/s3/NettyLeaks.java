package io.github.emadalblueshi.vertx.s3;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/**
 * Netty's leak reports during the tests (release gate 4). At the paranoid level (surefire sets
 * {@code io.netty.leakDetection.level}; {@link #install()} makes sure) Netty tracks every buffer,
 * and reports one that was garbage-collected without being released the next time it tracks an
 * allocation, through its logger. The reports are captured instead of printed, and returned by
 * {@link #collect()}.
 * <p>
 * Reflection is used because the tests run inside the library's module, which reads neither
 * Netty's modules nor {@code java.logging}; their packages are exported, so reflection may use them.
 * Public for the soak, whose checkpoints use the same check.
 */
public final class NettyLeaks {

  /** A safety net: a requested garbage collection completes in far less. */
  private static final long GC_TIMEOUT_MILLIS = 30_000;

  private static ByteArrayOutputStream reports;
  private static Object handler;
  /** Held strongly: {@code java.util.logging} keeps loggers only weakly. */
  private static Object logger;
  private static int taken;

  private NettyLeaks() {
  }

  /** Sets Netty's leak detection to paranoid and starts capturing its leak reports; once. */
  public static synchronized void install() {
    if (reports != null) {
      return;
    }
    try {
      Class<?> detector = Class.forName("io.netty.util.ResourceLeakDetector");
      Class<?> level = Class.forName("io.netty.util.ResourceLeakDetector$Level");
      Object paranoid = level.getField("PARANOID").get(null);
      if (detector.getMethod("getLevel").invoke(null) != paranoid) {
        detector.getMethod("setLevel", level).invoke(null, paranoid);
      }
      Class<?> loggerClass = Class.forName("java.util.logging.Logger");
      Class<?> handlerClass = Class.forName("java.util.logging.Handler");
      Class<?> levelClass = Class.forName("java.util.logging.Level");
      Object formatter = Class.forName("java.util.logging.SimpleFormatter").getConstructor().newInstance();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      handler = Class.forName("java.util.logging.StreamHandler")
        .getConstructor(OutputStream.class, Class.forName("java.util.logging.Formatter")).newInstance(out, formatter);
      handlerClass.getMethod("setEncoding", String.class).invoke(handler, "UTF-8");
      // Netty reports leaks as errors (SEVERE), all detectors through this logger
      handlerClass.getMethod("setLevel", levelClass).invoke(handler, levelClass.getField("SEVERE").get(null));
      logger = loggerClass.getMethod("getLogger", String.class).invoke(null, detector.getName());
      loggerClass.getMethod("addHandler", handlerClass).invoke(logger, handler);
      // Reported through the checks that fail on them, not on the console (the control leaks on purpose)
      loggerClass.getMethod("setUseParentHandlers", boolean.class).invoke(logger, false);
      reports = out;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot capture Netty's leak reports", e);
    }
  }

  /**
   * Makes Netty report every buffer that has become unreachable without being released, and
   * returns the reports since the last call, empty if there are none.
   * <p>
   * Two garbage collections, each confirmed by a sentinel reference reaching its queue: references
   * are enqueued one collection's batch after the other, so once the second sentinel arrives, every
   * buffer collected by the first is in Netty's queue. Then an allocation, which makes Netty drain
   * its queue and log the leaks before it returns.
   */
  public static synchronized String collect() {
    install();
    try {
      for (int collection = 0; collection < 2; collection++) {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> sentinel = new WeakReference<>(new Object(), queue);
        System.gc();
        if (queue.remove(GC_TIMEOUT_MILLIS) != sentinel) {
          throw new IllegalStateException("No garbage collection within " + GC_TIMEOUT_MILLIS + " ms");
        }
      }
      Object allocator = Class.forName("io.netty.buffer.PooledByteBufAllocator").getField("DEFAULT").get(null);
      Object buffer = Class.forName("io.netty.buffer.ByteBufAllocator").getMethod("directBuffer", int.class)
        .invoke(allocator, 1);
      Class.forName("io.netty.util.ReferenceCounted").getMethod("release").invoke(buffer);
      Class.forName("java.util.logging.Handler").getMethod("flush").invoke(handler);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot make Netty report leaks", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    String all = reports.toString(StandardCharsets.UTF_8);
    String fresh = all.substring(taken);
    taken = all.length();
    return fresh;
  }
}
