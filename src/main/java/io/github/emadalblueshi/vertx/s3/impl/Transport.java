package io.github.emadalblueshi.vertx.s3.impl;

import java.util.Map;

import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3Exception;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;

/**
 * Sends signed S3 requests over a Vert.x {@link HttpClientAgent} and buffers the responses.
 * <ul>
 *   <li>The wire path and query are produced by the same helpers the signer uses, so what is sent
 *   is exactly what is signed. The Host header (authority) is set explicitly and matches the
 *   signed value: the port is included only when it isn't the scheme default.</li>
 *   <li>Payload signing follows {@link PayloadMode}: {@code UNSIGNED-PAYLOAD} over HTTPS, signed
 *   over HTTP, where large bodies are hashed via {@code executeBlocking}.</li>
 *   <li>{@code Content-Length} is always set when a body is sent (and as {@code 0} for PUT/POST
 *   without a body). Redirects are never followed.</li>
 *   <li>Every request carries {@code User-Agent: vertx-s3-client/<version>}, unsigned.</li>
 *   <li>Non-2xx responses fail with {@code S3ServiceException} ({@link ErrorMapper}); everything
 *   else fails with {@link S3ClientException}.</li>
 * </ul>
 * The returned Futures are created through the {@link ContextSupport.Caller}; the client binds
 * the final result to the caller's context.
 */
final class Transport {

  private final ClientConfig config;
  private final HttpClientAgent http;
  private final S3SignatureV4 signer;

  Transport(Vertx vertx, ClientConfig config) {
    this.config = config;
    this.http = vertx.createHttpClient(config.httpClientOptions, config.poolOptions);
    this.signer = new S3SignatureV4(config.region, config.clock);
  }

  Future<S3Response> execute(ContextSupport.Caller caller, S3Request request) {
    S3Credentials credentials;
    try {
      credentials = config.credentialsProvider.credentials();
    } catch (RuntimeException e) {
      return caller.failed(new S3ClientException("The credentials provider failed", e));
    }
    if (credentials == null || isBlank(credentials.getAccessKey()) || isBlank(credentials.getSecretKey())) {
      return caller.failed(new S3ClientException("The credentials provider returned no access key or secret key", null));
    }
    return payloadHash(caller, request)
      .compose(payloadHash -> send(caller, request, credentials, payloadHash))
      .recover(error -> caller.failed(error instanceof S3Exception ? error
        : new S3ClientException("S3 request failed: " + error.getMessage(), error)));
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

  private Future<S3Response> send(ContextSupport.Caller caller, S3Request request, S3Credentials credentials,
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
    return http.request(options)
      .compose(httpRequest -> {
        HttpClientRequest withAuthority = httpRequest.authority(authority);
        return body == null ? withAuthority.send() : withAuthority.send(body);
      })
      .compose(response -> response.body()
        .map(responseBody -> new S3Response(response.statusCode(), response.statusMessage(), response.headers(), responseBody)))
      .compose(response -> response.isSuccess() ? caller.succeeded(response)
        : caller.failed(ErrorMapper.toException(response)));
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
