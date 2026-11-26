package io.github.emadalblueshi.vertx.s3.soak;

import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientConfig;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.VertxMetricsFactory;
import io.vertx.core.spi.metrics.ClientMetrics;
import io.vertx.core.spi.metrics.HttpClientMetrics;
import io.vertx.core.spi.metrics.PoolMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.core.spi.observability.HttpRequest;
import io.vertx.core.spi.observability.HttpResponse;

/**
 * Counts what the soak's HTTP clients hold, through Vert.x's metrics SPI, across all of them: open
 * connections, requests waiting for a connection, and requests in flight (sent, with a response
 * that has neither ended nor been reset), each of which holds a connection. Vert.x creates pool
 * metrics only for clients that have HTTP client metrics, so both are provided. Vert.x 5.2's HTTP
 * client reports only the wait for a connection through pool metrics, not how long one is held.
 */
final class SoakMetrics implements VertxMetricsFactory {

  final AtomicLong connections = new AtomicLong();
  final AtomicLong queued = new AtomicLong();
  final AtomicLong inFlight = new AtomicLong();

  @Override
  public VertxMetrics metrics(VertxOptions options) {
    return new VertxMetrics() {
      @Override
      public HttpClientMetrics<?, ?> createHttpClientMetrics(HttpClientConfig config) {
        return new HttpClientMetrics<Request, Object>() {
          @Override
          public ClientMetrics<Request, HttpRequest, HttpResponse> createEndpointMetrics(SocketAddress remoteAddress,
                                                                                        int maxPoolSize) {
            return new Endpoint();
          }
        };
      }

      @Override
      public PoolMetrics<?, ?> createPoolMetrics(String type, String name, int maxSize) {
        return "http".equals(type) ? new Pool() : null;
      }
    };
  }

  /** One request, so that it leaves the in-flight count once, however it ends. */
  private static final class Request {
    private boolean begun;
    private boolean done;
  }

  private final class Endpoint implements ClientMetrics<Request, HttpRequest, HttpResponse> {

    @Override
    public Request init() {
      return new Request();
    }

    @Override
    public void requestBegin(Request request, String uri, HttpRequest httpRequest) {
      synchronized (request) {
        if (request.begun) {
          return;
        }
        request.begun = true;
      }
      inFlight.incrementAndGet();
    }

    @Override
    public void responseEnd(Request request) {
      end(request);
    }

    @Override
    public void responseEnd(Request request, long bytesRead) {
      end(request);
    }

    @Override
    public void requestReset(Request request) {
      end(request);
    }

    private void end(Request request) {
      if (request == null) {
        return;
      }
      synchronized (request) {
        if (!request.begun || request.done) {
          return;
        }
        request.done = true;
      }
      inFlight.decrementAndGet();
    }

    @Override
    public void connected() {
      connections.incrementAndGet();
    }

    @Override
    public void disconnected() {
      connections.decrementAndGet();
    }
  }

  private final class Pool implements PoolMetrics<Object, Object> {

    @Override
    public Object enqueue() {
      queued.incrementAndGet();
      return null;
    }

    @Override
    public void dequeue(Object queueMetric) {
      queued.decrementAndGet();
    }
  }
}
