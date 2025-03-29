package io.github.emadalblueshi.objectstorage.client.s3;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class BufferQueueReadStream implements ReadStream<Buffer> {

  private final ReadStream<Buffer> stream;
  final int bufferSize = 5 * 1024 * 1024;
  private Handler<Buffer> handler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  private final Queue<Buffer> bufferQueue = new LinkedList<>();
  private int bufferQueueSize;
  private boolean streamEnded;

  public BufferQueueReadStream(ReadStream<Buffer> readStream) {
    this.stream = Objects.requireNonNull(readStream, "Read stream must not be null");
  }

  @Override
  public BufferQueueReadStream pause() {
    stream.pause();
    return this;
  }

  @Override
  public BufferQueueReadStream resume() {
    if (!streamEnded) {
      stream.resume();
    } else {
      handleEnd();
    }
    return this;
  }

  @Override
  public BufferQueueReadStream fetch(long amount) {
    if (!streamEnded) {
      stream.fetch(amount);
    } else {
      handleEnd();
    }
    return this;
  }

  @Override
  public BufferQueueReadStream handler(Handler<Buffer> handler) {
    this.handler = handler;
    if (handler == null) {
      stream.handler(null);
      stream.exceptionHandler(null);
      stream.endHandler(null);
      return this;
    }
    stream.endHandler(v -> {
      streamEnded = true;
      handleChunks();
    }).exceptionHandler(e -> {
      if (exceptionHandler != null) {
        exceptionHandler.handle(e);
      }
    }).handler(buff -> {
      if (buff != null && buff.length() != 0) {
        bufferQueue.add(buff);
        bufferQueueSize += buff.length();
        handleChunks();
      }
    });
    return this;
  }

  @Override
  public BufferQueueReadStream exceptionHandler(Handler<Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
    return this;
  }

  @Override
  public BufferQueueReadStream endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  private void handleChunks() {
    // Handle full buffer chunk
    while (bufferQueueSize >= bufferSize) {
      Buffer chunk = Buffer.buffer(bufferSize);
      int remaining = bufferSize;
      while (remaining > 0 && !bufferQueue.isEmpty()) {
        Buffer head = bufferQueue.poll();
        bufferQueueSize -= head.length();
        if (head.length() <= remaining) {
          chunk.appendBuffer(head);
          remaining -= head.length();
        } else {
          chunk.appendBuffer(head.slice(0, remaining));
          Buffer remainingBuffer = head.slice(remaining, head.length());
          if (remainingBuffer.length() > 0) {
            bufferQueue.add(remainingBuffer);
            bufferQueueSize += remainingBuffer.length();
          }
          remaining = 0;
        }
      }
      if (handler != null) {
        handler.handle(chunk);
      }
    }

    // Handles remaining buffer when the stream ends
    if (streamEnded && bufferQueueSize > 0) {
      Buffer chunk = Buffer.buffer(bufferQueueSize);
      while (!bufferQueue.isEmpty()) {
        Buffer buffer = bufferQueue.poll();
        chunk.appendBuffer(buffer);
        bufferQueueSize -= buffer.length();
      }
      if (handler != null) {
        handler.handle(chunk);
      }
    }
  }

  private void handleEnd() {
    if (endHandler != null) {
      endHandler.handle(null);
      endHandler = null;
    }
  }
}
