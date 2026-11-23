package io.github.emadalblueshi.vertx.s3.impl;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Records what Vert.x logs at {@code WARNING} or above, through its default backend
 * ({@code java.util.logging}), while it is open; meanwhile those records don't reach the console,
 * since the tests using it assert on them. Reflection is used because the tests run inside
 * the library's module, which doesn't read {@code java.logging}; the package is exported, so
 * reflection may use it.
 */
final class VertxLogCapture implements AutoCloseable {

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  /** Held strongly: {@code java.util.logging} keeps loggers only weakly. */
  private final Object logger;
  private final Object handler;
  private final Class<?> loggerClass;
  private final Class<?> handlerClass;
  private final boolean useParentHandlers;

  private VertxLogCapture() throws ReflectiveOperationException {
    loggerClass = Class.forName("java.util.logging.Logger");
    handlerClass = Class.forName("java.util.logging.Handler");
    Class<?> streamHandlerClass = Class.forName("java.util.logging.StreamHandler");
    Class<?> formatterClass = Class.forName("java.util.logging.Formatter");
    Class<?> levelClass = Class.forName("java.util.logging.Level");
    Object formatter = Class.forName("java.util.logging.SimpleFormatter").getConstructor().newInstance();
    handler = streamHandlerClass.getConstructor(OutputStream.class, formatterClass).newInstance(out, formatter);
    handlerClass.getMethod("setLevel", levelClass).invoke(handler, levelClass.getField("WARNING").get(null));
    logger = loggerClass.getMethod("getLogger", String.class).invoke(null, "io.vertx");
    loggerClass.getMethod("addHandler", handlerClass).invoke(logger, handler);
    useParentHandlers = (Boolean) loggerClass.getMethod("getUseParentHandlers").invoke(logger);
    loggerClass.getMethod("setUseParentHandlers", boolean.class).invoke(logger, false);
  }

  static VertxLogCapture start() {
    try {
      return new VertxLogCapture();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot capture the Vert.x log", e);
    }
  }

  /** Everything logged since {@link #start()}, formatted; empty if nothing was. */
  String logged() {
    try {
      handlerClass.getMethod("flush").invoke(handler);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    try {
      loggerClass.getMethod("removeHandler", handlerClass).invoke(logger, handler);
      loggerClass.getMethod("setUseParentHandlers", boolean.class).invoke(logger, useParentHandlers);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
