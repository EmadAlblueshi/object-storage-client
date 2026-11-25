package io.github.emadalblueshi.vertx.s3.impl;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records what is logged through {@code java.util.logging}, the default backend of Vert.x and of
 * Netty, under one logger while it is open: formatted, with the stack traces of logged exceptions.
 * <ul>
 *   <li>{@link #start()}: what Vert.x logs at {@code WARNING} or above.</li>
 *   <li>{@link #library()}: what the client logs at {@code WARNING} or above.</li>
 *   <li>{@link #everything()}: every record of every logger, at every level.</li>
 * </ul>
 * The first two keep their records off the console, since the tests using them assert on them.
 * Reflection is used because the tests run inside the library's module, which doesn't read
 * {@code java.logging}; the package is exported, so reflection may use it.
 */
final class VertxLogCapture implements AutoCloseable {

  /** A row of a Netty hex dump (Netty's {@code LoggingHandler}, which {@code logActivity} adds): offset, bytes. */
  private static final Pattern HEX_DUMP_ROW = Pattern.compile("^\\|[0-9a-f]{8}\\|((?: [0-9a-f]{2})+)", Pattern.MULTILINE);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  /** Held strongly: {@code java.util.logging} keeps loggers only weakly. */
  private final Object logger;
  private final Object handler;
  private final Class<?> loggerClass;
  private final Class<?> handlerClass;
  private final Class<?> levelClass;
  private final boolean silenced;
  private final boolean useParentHandlers;
  private final boolean lowered;
  /** The logger's own level before the capture lowered it (often {@code null}: inherited). */
  private final Object previousLevel;

  private VertxLogCapture(String loggerName, String minimumLevel, boolean silence, boolean lowerLevel)
      throws ReflectiveOperationException {
    loggerClass = Class.forName("java.util.logging.Logger");
    handlerClass = Class.forName("java.util.logging.Handler");
    levelClass = Class.forName("java.util.logging.Level");
    Class<?> streamHandlerClass = Class.forName("java.util.logging.StreamHandler");
    Class<?> formatterClass = Class.forName("java.util.logging.Formatter");
    Object formatter = Class.forName("java.util.logging.SimpleFormatter").getConstructor().newInstance();
    Object minimum = levelClass.getField(minimumLevel).get(null);
    handler = streamHandlerClass.getConstructor(OutputStream.class, formatterClass).newInstance(out, formatter);
    handlerClass.getMethod("setEncoding", String.class).invoke(handler, "UTF-8");
    handlerClass.getMethod("setLevel", levelClass).invoke(handler, minimum);
    logger = loggerClass.getMethod("getLogger", String.class).invoke(null, loggerName);
    lowered = lowerLevel;
    previousLevel = loggerClass.getMethod("getLevel").invoke(logger);
    if (lowerLevel) {
      loggerClass.getMethod("setLevel", levelClass).invoke(logger, minimum);
    }
    loggerClass.getMethod("addHandler", handlerClass).invoke(logger, handler);
    silenced = silence;
    useParentHandlers = (Boolean) loggerClass.getMethod("getUseParentHandlers").invoke(logger);
    if (silence) {
      loggerClass.getMethod("setUseParentHandlers", boolean.class).invoke(logger, false);
    }
  }

  /** What Vert.x logs (logger {@code io.vertx}) at {@code WARNING} or above, kept off the console. */
  static VertxLogCapture start() {
    return create("io.vertx", "WARNING", true, false);
  }

  /** What the client logs (logger {@code io.github.emadalblueshi.vertx.s3}) at {@code WARNING} or above, kept off the console. */
  static VertxLogCapture library() {
    return create("io.github.emadalblueshi.vertx.s3", "WARNING", true, false);
  }

  /**
   * Every record of every logger at every level, Vert.x's and Netty's debug records included: the
   * root logger's level is lowered to {@code ALL} while the capture is open. The console handler
   * keeps its own level, so the console shows no more than before.
   */
  static VertxLogCapture everything() {
    return create("", "ALL", false, true);
  }

  private static VertxLogCapture create(String loggerName, String minimumLevel, boolean silence, boolean lowerLevel) {
    try {
      return new VertxLogCapture(loggerName, minimumLevel, silence, lowerLevel);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot capture the log", e);
    }
  }

  /** Everything logged since the capture started, formatted; empty if nothing was. */
  String logged() {
    try {
      handlerClass.getMethod("flush").invoke(handler);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * The bytes of every Netty hex dump logged so far (the format {@code logActivity} logs in by
   * default), in order, as ISO-8859-1 text: a header split across dump rows reads as one line.
   */
  String hexDumps() {
    Matcher row = HEX_DUMP_ROW.matcher(logged());
    StringBuilder bytes = new StringBuilder();
    while (row.find()) {
      for (String hex : row.group(1).trim().split(" ")) {
        bytes.append((char) Integer.parseInt(hex, 16));
      }
    }
    return bytes.toString();
  }

  @Override
  public void close() {
    try {
      handlerClass.getMethod("flush").invoke(handler);
      loggerClass.getMethod("removeHandler", handlerClass).invoke(logger, handler);
      if (silenced) {
        loggerClass.getMethod("setUseParentHandlers", boolean.class).invoke(logger, useParentHandlers);
      }
      if (lowered) {
        loggerClass.getMethod("setLevel", levelClass).invoke(logger, previousLevel);
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
