package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.vertx.core.VertxException;

class S3ExceptionTest {

  @Test
  void serviceExceptionIsAVertxExceptionWithoutStackTrace() {
    S3ServiceException e = new S3ServiceException("not found", 404, "NoSuchKey", "req-1", "host-1", "/b/k",
      Collections.emptyMap());
    assertTrue(e instanceof VertxException);
    assertEquals(0, e.getStackTrace().length);
    e.fillInStackTrace();
    assertEquals(0, e.getStackTrace().length);
    assertEquals("not found", e.getMessage());
    assertEquals(404, e.getStatusCode());
  }

  @Test
  void clientExceptionIsAVertxExceptionWithStackTraceAndCause() {
    IllegalStateException cause = new IllegalStateException("boom");
    S3ClientException e = new S3ClientException("failed", cause);
    assertTrue(e instanceof VertxException);
    assertTrue(e.getStackTrace().length > 0);
    assertEquals(S3ExceptionTest.class.getName(), e.getStackTrace()[0].getClassName());
    assertSame(cause, e.getCause());
  }
}
