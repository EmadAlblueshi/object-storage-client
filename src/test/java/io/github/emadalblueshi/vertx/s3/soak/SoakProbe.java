package io.github.emadalblueshi.vertx.s3.soak;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Readings of the soak's own process: heap, direct memory (the JVM's and Netty's counters), threads,
 * open files, resident memory, and its TCP sockets to the server by state. Linux only ({@code /proc}).
 * Reflection is used because the tests run inside the library's module, which doesn't read
 * {@code java.management}; the packages are exported, so reflection may use them.
 */
final class SoakProbe {

  /** TCP states in {@code /proc/net/tcp}. */
  private static final String ESTABLISHED = "01";
  private static final String CLOSE_WAIT = "08";

  private final int serverPort;
  private final Object memoryBean;
  private final Method heapUsage;
  private final Method used;
  private final Object threadBean;
  private final Method threadCount;
  private final Object directPool;
  private final Method directUsed;
  private final Method nettyDirectUsed;

  SoakProbe(int serverPort) throws ReflectiveOperationException {
    this.serverPort = serverPort;
    Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
    memoryBean = factory.getMethod("getMemoryMXBean").invoke(null);
    heapUsage = Class.forName("java.lang.management.MemoryMXBean").getMethod("getHeapMemoryUsage");
    used = Class.forName("java.lang.management.MemoryUsage").getMethod("getUsed");
    threadBean = factory.getMethod("getThreadMXBean").invoke(null);
    threadCount = Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadCount");
    Class<?> bufferPool = Class.forName("java.lang.management.BufferPoolMXBean");
    Method name = bufferPool.getMethod("getName");
    Object direct = null;
    for (Object pool : (List<?>) factory.getMethod("getPlatformMXBeans", Class.class).invoke(null, bufferPool)) {
      if ("direct".equals(name.invoke(pool))) {
        direct = pool;
      }
    }
    directPool = direct;
    directUsed = bufferPool.getMethod("getMemoryUsed");
    nettyDirectUsed = Class.forName("io.netty.util.internal.PlatformDependent").getMethod("usedDirectMemory");
  }

  long heapUsed() {
    return (Long) call(used, call(heapUsage, memoryBean));
  }

  /** Direct buffers the JVM tracks ({@code BufferPoolMXBean} "direct"). */
  long jvmDirectUsed() {
    return directPool == null ? -1 : (Long) call(directUsed, directPool);
  }

  /** Direct memory Netty's allocators use ({@code PlatformDependent.usedDirectMemory()}). */
  long nettyDirectUsed() {
    return (Long) call(nettyDirectUsed, null);
  }

  int threads() {
    return (Integer) call(threadCount, threadBean);
  }

  long openFiles() {
    try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
      return fds.count();
    } catch (IOException e) {
      return -1;
    }
  }

  /** Resident set size, for the report only: the JVM rarely gives memory back. */
  long residentBytes() {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
        if (line.startsWith("VmRSS:")) {
          return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
        }
      }
    } catch (IOException | NumberFormatException e) {
      // reported as unknown
    }
    return -1;
  }

  /** This network namespace's TCP sockets whose remote port is the server's, by state. */
  Sockets sockets() {
    int established = 0;
    int closeWait = 0;
    for (String table : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
      List<String> lines;
      try {
        lines = Files.readAllLines(Path.of(table));
      } catch (IOException e) {
        continue;
      }
      for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 4) {
          continue;
        }
        String remote = fields[2];
        int port = Integer.parseInt(remote.substring(remote.indexOf(':') + 1), 16);
        if (port != serverPort) {
          continue;
        }
        if (ESTABLISHED.equals(fields[3])) {
          established++;
        } else if (CLOSE_WAIT.equals(fields[3])) {
          closeWait++;
        }
      }
    }
    return new Sockets(established, closeWait);
  }

  /** A HotSpot option's value, from {@code HotSpotDiagnosticMXBean}. */
  static String vmOption(String name) throws ReflectiveOperationException {
    Class<?> diagnostic = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
    Object bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getPlatformMXBean", Class.class)
      .invoke(null, diagnostic);
    Object option = diagnostic.getMethod("getVMOption", String.class).invoke(bean, name);
    return (String) Class.forName("com.sun.management.VMOption").getMethod("getValue").invoke(option);
  }

  private static Object call(Method method, Object target) {
    try {
      return method.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static final class Sockets {
    final int established;
    final int closeWait;

    Sockets(int established, int closeWait) {
      this.established = established;
      this.closeWait = closeWait;
    }
  }
}
