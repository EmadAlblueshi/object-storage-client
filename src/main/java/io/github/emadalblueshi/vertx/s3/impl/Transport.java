package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Map;
import java.util.function.LongFunction;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3Exception;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;

/**
 * Sends signed S3 requests over a Vert.x {@link HttpClientAgent}.
 * <ul>
 *   <li>The wire path and query are produced by the same helpers the signer uses, so what is sent
 *   is exactly what is signed. The Host header (authority) is set explicitly and matches the
 *   signed value: the port is included only when it isn't the scheme default.</li>
 *   <li>Payload signing follows {@link PayloadMode}: {@code UNSIGNED-PAYLOAD} over HTTPS, signed
 *   over HTTP, where large bodies are hashed via {@code executeBlocking}.</li>
 *   <li>{@code Content-Length} is always set when a body is sent (and as {@code 0} for PUT/POST
 *   without a body). Redirects are never followed.</li>
 *   <li>Every request carries {@code User-Agent: vertx-s3-client/<version>}, unsigned.</li>
 *   <li>The HTTP request is issued on the caller's context, so response events (and the handlers
 *   of a streamed body) run there.</li>
 *   <li>Buffered bodies are size-limited: {@link #MAX_BUFFERED_RESPONSE_SIZE} for XML results,
 *   a caller-supplied limit for {@code getObject}. Error bodies are read up to that size and
 *   truncated beyond it. A body over its limit is never read further: the request is cancelled,
 *   which closes the connection.</li>
 *   <li>Non-2xx responses fail with {@code S3ServiceException} ({@link ErrorMapper}); everything
 *   else fails with {@link S3ClientException}.</li>
 * </ul>
 * The returned Futures are created through the {@link ContextSupport.Caller}; the client binds
 * the final result to the caller's context.
 */
final class Transport {

  /**
   * The limit for buffered XML results and error bodies: a full ListObjectsV2 page (1000 keys of
   * up to 1024 bytes plus metadata) stays well below it.
   */
  static final int MAX_BUFFERED_RESPONSE_SIZE = 4 * 1024 * 1024;

  private final ClientConfig config;
  private final HttpClientAgent http;
  private final S3SignatureV4 signer;

  Transport(Vertx vertx, ClientConfig config) {
    this.config = config;
    this.http = vertx.createHttpClient(config.httpClientOptions, config.poolOptions);
    this.signer = new S3SignatureV4(config.region, config.clock);
  }

  /** Sends {@code request} and buffers a response of at most {@link #MAX_BUFFERED_RESPONSE_SIZE}. */
  Future<S3Response> execute(ContextSupport.Caller caller, S3Request request) {
    return execute(caller, request, MAX_BUFFERED_RESPONSE_SIZE,
      size -> new S3ClientException("The response body exceeds the limit of " + MAX_BUFFERED_RESPONSE_SIZE
        + " bytes" + (size >= 0 ? " (Content-Length " + size + ")" : ""), null));
  }

  /**
   * Sends {@code request} and buffers a successful response of at most {@code limit} bytes.
   *
   * @param tooLarge the failure for a body over the limit, given its {@code Content-Length}
   *     ({@code -1} when the length isn't known in advance)
   */
  Future<S3Response> execute(ContextSupport.Caller caller, S3Request request, int limit,
                             LongFunction<S3ClientException> tooLarge) {
    return recover(caller, open(caller, request).compose(response -> {
      if (!isSuccess(response)) {
        return failWithError(caller, response);
      }
      return readBody(caller, response, limit, tooLarge)
        .map(body -> new S3Response(response.statusCode(), response.statusMessage(), response.headers(), body));
    }));
  }

  /**
   * Sends {@code request} and, on success, returns the response <em>paused</em> with its body
   * unread, so no data is lost before the caller attaches handlers. Error responses are read
   * (truncated at {@link #MAX_BUFFERED_RESPONSE_SIZE}) and fail the Future before any body is
   * handed out.
   */
  Future<HttpClientResponse> stream(ContextSupport.Caller caller, S3Request request) {
    return recover(caller, open(caller, request).compose(response -> {
      if (!isSuccess(response)) {
        return failWithError(caller, response);
      }
      response.pause();
      return caller.succeeded(response);
    }));
  }

  Future<Void> close() {
    return http.close();
  }

  /** The {@code x-amz-content-sha256} value for {@code request}. Package-private for tests. */
  Future<String> payloadHash(ContextSupport.Caller caller, S3Request request) {
    Buffer body = request.body;
    switch (PayloadMode.decide(config.tls, body == null ? -1 : body.length())) {
      case EMPTY:
        return caller.succeeded(S3SignatureV4.EMPTY_PAYLOAD_SHA256);
      case UNSIGNED:
        return caller.succeeded(S3SignatureV4.UNSIGNED_PAYLOAD);
      case INLINE:
        return caller.succeeded(HashUtil.sha256Hex(body.getBytes()));
      case OFFLOAD:
      default:
        return caller.executeBlocking(() -> HashUtil.sha256Hex(body.getBytes()));
    }
  }

  /** Signs and sends {@code request} from the caller's context; the response body is not read yet. */
  private Future<HttpClientResponse> open(ContextSupport.Caller caller, S3Request request) {
    S3Credentials credentials;
    try {
      credentials = config.credentialsProvider.credentials();
    } catch (RuntimeException e) {
      return caller.failed(new S3ClientException("The credentials provider failed", e));
    }
    if (credentials == null || isBlank(credentials.getAccessKey()) || isBlank(credentials.getSecretKey())) {
      return caller.failed(new S3ClientException("The credentials provider returned no access key or secret key", null));
    }
    return payloadHash(caller, request).compose(payloadHash -> send(caller, request, credentials, payloadHash));
  }

  private Future<HttpClientResponse> send(ContextSupport.Caller caller, S3Request request, S3Credentials credentials,
                                          String payloadHash) {
    String host = config.hostFor(request.bucket);
    String hostHeader = S3SignatureV4.hostHeader(host, config.defaultPort ? -1 : config.port, config.tls);
    String path = config.pathFor(request.bucket, request.key);
    S3SignatureV4.Signed signed;
    try {
      signed = signer.sign(new S3SignatureV4.Request(request.method.name(), hostHeader, path, request.query,
        request.headers, payloadHash), credentials.getAccessKey(), credentials.getSecretKey(), credentials.getSessionToken());
    } catch (IllegalArgumentException e) {
      return caller.failed(new S3ClientException("Cannot sign the request: " + e.getMessage(), e));
    }

    String query = S3SignatureV4.canonicalQuery(request.query);
    RequestOptions options = new RequestOptions()
      .setMethod(request.method)
      .setHost(host)
      .setPort(config.port)
      .setSsl(config.tls)
      .setURI(S3SignatureV4.encodePath(path) + (query.isEmpty() ? "" : "?" + query))
      .setFollowRedirects(false);
    for (Map.Entry<String, String> header : request.headers) {
      options.addHeader(header.getKey(), header.getValue());
    }
    for (Map.Entry<String, String> header : signed.headers) {
      options.putHeader(header.getKey(), header.getValue());
    }
    // Not signed: proxies may rewrite it, and the AWS SDKs don't sign it either
    options.putHeader(HttpHeaders.USER_AGENT, UserAgent.VALUE);
    Buffer body = request.body;
    if (body != null) {
      options.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
    } else if (request.method == HttpMethod.PUT || request.method == HttpMethod.POST) {
      options.putHeader(HttpHeaders.CONTENT_LENGTH, "0");
    }

    HostAndPort authority = HostAndPort.authority(host, config.defaultPort ? -1 : config.port);
    return caller.onContext(() -> http.request(options)
      .compose(httpRequest -> {
        HttpClientRequest withAuthority = httpRequest.authority(authority);
        return body == null ? withAuthority.send() : withAuthority.send(body);
      }));
  }

  /** Reads an error response (truncated at the buffered limit) and fails with the mapped exception. */
  private <T> Future<T> failWithError(ContextSupport.Caller caller, HttpClientResponse response) {
    return readBody(caller, response, MAX_BUFFERED_RESPONSE_SIZE, null)
      .compose(body -> caller.failed(ErrorMapper.toException(
        new S3Response(response.statusCode(), response.statusMessage(), response.headers(), body))));
  }

  /**
   * Buffers the body of {@code response}, which must not have been read yet (the handlers are
   * attached in the same context task that delivered the response head, so no chunk is missed).
   *
   * @param tooLarge the failure for a body over {@code limit}, or {@code null} to truncate
   *     instead: the first {@code limit} bytes are kept and the rest is not read
   */
  private static Future<Buffer> readBody(ContextSupport.Caller caller, HttpClientResponse response, int limit,
                                         LongFunction<S3ClientException> tooLarge) {
    // A HEAD response's Content-Length describes the object, not a body
    long contentLength = response.request().getMethod() == HttpMethod.HEAD ? 0 : contentLength(response);
    if (tooLarge != null && contentLength > limit) {
      response.request().cancel();
      return caller.failed(tooLarge.apply(contentLength));
    }
    Promise<Buffer> promise = caller.promise();
    Buffer body = Buffer.buffer((int) Math.max(0, Math.min(contentLength, limit)));
    response.handler(chunk -> {
      if (body.length() + chunk.length() <= limit) {
        body.appendBuffer(chunk);
        return;
      }
      if (tooLarge == null) {
        body.appendBuffer(chunk, 0, limit - body.length());
        promise.tryComplete(body);
      } else {
        promise.tryFail(tooLarge.apply(contentLength));
      }
      response.handler(null);
      response.request().cancel();
    });
    response.endHandler(v -> promise.tryComplete(body));
    response.exceptionHandler(promise::tryFail);
    return promise.future();
  }

  /** Wraps any failure that isn't already an {@link S3Exception} in an {@link S3ClientException}. */
  private static <T> Future<T> recover(ContextSupport.Caller caller, Future<T> future) {
    return future.recover(error -> caller.failed(error instanceof S3Exception ? error
      : new S3ClientException("S3 request failed: " + error.getMessage(), error)));
  }

  private static boolean isSuccess(HttpClientResponse response) {
    return response.statusCode() >= 200 && response.statusCode() < 300;
  }

  /** The response's {@code Content-Length}, or {@code -1} if absent or invalid. */
  static long contentLength(HttpClientResponse response) {
    String value = response.getHeader(HttpHeaders.CONTENT_LENGTH);
    if (value == null) {
      return -1;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
