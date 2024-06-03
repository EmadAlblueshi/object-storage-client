package io.github.emadalblueshi.objectstorage.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.SSLEngineOptions;
import io.vertx.core.net.TrustOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.uritemplate.ExpandOptions;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class ObjectStorageClientOptions extends WebClientOptions {

  private S3Options s3Options;

  public ObjectStorageClientOptions setS3Options(S3Options s3Options) {
    this.s3Options = s3Options;
    return this;
  }

  public S3Options getS3Options() {
    return s3Options;
  }

  @Override
  public ObjectStorageClientOptions setUserAgentEnabled(boolean userAgentEnabled) {
    return (ObjectStorageClientOptions) super.setUserAgentEnabled(userAgentEnabled);
  }

  @Override
  public ObjectStorageClientOptions setUserAgent(String userAgent) {
    return (ObjectStorageClientOptions) super.setUserAgent(userAgent);
  }

  @Override
  public ObjectStorageClientOptions setFollowRedirects(boolean followRedirects) {
    return (ObjectStorageClientOptions) super.setFollowRedirects(followRedirects);
  }

  @Override
  public ObjectStorageClientOptions setTemplateExpandOptions(ExpandOptions templateExpandOptions) {
    return (ObjectStorageClientOptions) super.setTemplateExpandOptions(templateExpandOptions);
  }

  @Override
  public ObjectStorageClientOptions setMaxRedirects(int maxRedirects) {
    return (ObjectStorageClientOptions) super.setMaxRedirects(maxRedirects);
  }

  @Override
  public ObjectStorageClientOptions setSendBufferSize(int sendBufferSize) {
    return (ObjectStorageClientOptions) super.setSendBufferSize(sendBufferSize);
  }

  @Override
  public ObjectStorageClientOptions setReceiveBufferSize(int receiveBufferSize) {
    return (ObjectStorageClientOptions) super.setReceiveBufferSize(receiveBufferSize);
  }

  @Override
  public ObjectStorageClientOptions setReuseAddress(boolean reuseAddress) {
    return (ObjectStorageClientOptions) super.setReuseAddress(reuseAddress);
  }

  @Override
  public ObjectStorageClientOptions setTrafficClass(int trafficClass) {
    return (ObjectStorageClientOptions) super.setTrafficClass(trafficClass);
  }

  @Override
  public ObjectStorageClientOptions setTcpNoDelay(boolean tcpNoDelay) {
    return (ObjectStorageClientOptions) super.setTcpNoDelay(tcpNoDelay);
  }

  @Override
  public ObjectStorageClientOptions setTcpKeepAlive(boolean tcpKeepAlive) {
    return (ObjectStorageClientOptions) super.setTcpKeepAlive(tcpKeepAlive);
  }

  @Override
  public ObjectStorageClientOptions setSoLinger(int soLinger) {
    return (ObjectStorageClientOptions) super.setSoLinger(soLinger);
  }

  @Override
  public ObjectStorageClientOptions setIdleTimeout(int idleTimeout) {
    return (ObjectStorageClientOptions) super.setIdleTimeout(idleTimeout);
  }

  @Override
  public ObjectStorageClientOptions setIdleTimeoutUnit(TimeUnit idleTimeoutUnit) {
    return (ObjectStorageClientOptions) super.setIdleTimeoutUnit(idleTimeoutUnit);
  }

  @Override
  public ObjectStorageClientOptions setSsl(boolean ssl) {
    return (ObjectStorageClientOptions) super.setSsl(ssl);
  }

  @Override
  public ObjectStorageClientOptions setKeyCertOptions(KeyCertOptions options) {
    return (ObjectStorageClientOptions) super.setKeyCertOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setKeyStoreOptions(JksOptions options) {
    return (ObjectStorageClientOptions) super.setKeyStoreOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setPfxKeyCertOptions(PfxOptions options) {
    return (ObjectStorageClientOptions) super.setPfxKeyCertOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setTrustOptions(TrustOptions options) {
    return (ObjectStorageClientOptions) super.setTrustOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setPemKeyCertOptions(PemKeyCertOptions options) {
    return (ObjectStorageClientOptions) super.setPemKeyCertOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setTrustStoreOptions(JksOptions options) {
    return (ObjectStorageClientOptions) super.setTrustStoreOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setPfxTrustOptions(PfxOptions options) {
    return (ObjectStorageClientOptions) super.setPfxTrustOptions(options);
  }

  @Override
  public ObjectStorageClientOptions setPemTrustOptions(PemTrustOptions options) {
    return (ObjectStorageClientOptions) super.setPemTrustOptions(options);
  }

  @Override
  public ObjectStorageClientOptions addEnabledCipherSuite(String suite) {
    return (ObjectStorageClientOptions) super.addEnabledCipherSuite(suite);
  }

  @Override
  public ObjectStorageClientOptions addCrlPath(String crlPath) throws NullPointerException {
    return (ObjectStorageClientOptions) super.addCrlPath(crlPath);
  }

  @Override
  public ObjectStorageClientOptions addCrlValue(Buffer crlValue) throws NullPointerException {
    return (ObjectStorageClientOptions) super.addCrlValue(crlValue);
  }

  @Override
  public ObjectStorageClientOptions setConnectTimeout(int connectTimeout) {
    return (ObjectStorageClientOptions) super.setConnectTimeout(connectTimeout);
  }

  @Override
  public ObjectStorageClientOptions setTrustAll(boolean trustAll) {
    return (ObjectStorageClientOptions) super.setTrustAll(trustAll);
  }

  @Override
  public ObjectStorageClientOptions setMaxPoolSize(int maxPoolSize) {
    return (ObjectStorageClientOptions) super.setMaxPoolSize(maxPoolSize);
  }

  @Override
  public ObjectStorageClientOptions setHttp2MultiplexingLimit(int limit) {
    return (ObjectStorageClientOptions) super.setHttp2MultiplexingLimit(limit);
  }

  @Override
  public ObjectStorageClientOptions setHttp2MaxPoolSize(int max) {
    return (ObjectStorageClientOptions) super.setHttp2MaxPoolSize(max);
  }

  @Override
  public ObjectStorageClientOptions setHttp2ConnectionWindowSize(int http2ConnectionWindowSize) {
    return (ObjectStorageClientOptions) super.setHttp2ConnectionWindowSize(http2ConnectionWindowSize);
  }

  @Override
  public ObjectStorageClientOptions setKeepAlive(boolean keepAlive) {
    return (ObjectStorageClientOptions) super.setKeepAlive(keepAlive);
  }

  @Override
  public ObjectStorageClientOptions setPipelining(boolean pipelining) {
    return (ObjectStorageClientOptions) super.setPipelining(pipelining);
  }

  @Override
  public ObjectStorageClientOptions setPipeliningLimit(int limit) {
    return (ObjectStorageClientOptions) super.setPipeliningLimit(limit);
  }

  @Override
  public ObjectStorageClientOptions setVerifyHost(boolean verifyHost) {
    return (ObjectStorageClientOptions) super.setVerifyHost(verifyHost);
  }

  @Override
  @Deprecated
  public ObjectStorageClientOptions setTryUseCompression(boolean tryUseCompression) {
    return (ObjectStorageClientOptions) super.setTryUseCompression(tryUseCompression);
  }

  @Override
  public ObjectStorageClientOptions setDecompressionSupported(boolean tryUseCompression) {
    return (ObjectStorageClientOptions) super.setDecompressionSupported(tryUseCompression);
  }

  @Override
  public ObjectStorageClientOptions setSendUnmaskedFrames(boolean sendUnmaskedFrames) {
    return (ObjectStorageClientOptions) super.setSendUnmaskedFrames(sendUnmaskedFrames);
  }

  @Override
  public ObjectStorageClientOptions setMaxWebSocketFrameSize(int maxWebsocketFrameSize) {
    return (ObjectStorageClientOptions) super.setMaxWebSocketFrameSize(maxWebsocketFrameSize);
  }

  @Override
  public ObjectStorageClientOptions setDefaultHost(String defaultHost) {
    return (ObjectStorageClientOptions) super.setDefaultHost(defaultHost);
  }

  @Override
  public ObjectStorageClientOptions setDefaultPort(int defaultPort) {
    return (ObjectStorageClientOptions) super.setDefaultPort(defaultPort);
  }

  @Override
  public ObjectStorageClientOptions setMaxChunkSize(int maxChunkSize) {
    return (ObjectStorageClientOptions) super.setMaxChunkSize(maxChunkSize);
  }

  @Override
  public ObjectStorageClientOptions setProtocolVersion(HttpVersion protocolVersion) {
    return (ObjectStorageClientOptions) super.setProtocolVersion(protocolVersion);
  }

  @Override
  public ObjectStorageClientOptions setMaxHeaderSize(int maxHeaderSize) {
    return (ObjectStorageClientOptions) super.setMaxHeaderSize(maxHeaderSize);
  }

  @Override
  public ObjectStorageClientOptions setMaxWaitQueueSize(int maxWaitQueueSize) {
    return (ObjectStorageClientOptions) super.setMaxWaitQueueSize(maxWaitQueueSize);
  }

  @Override
  public ObjectStorageClientOptions setUseAlpn(boolean useAlpn) {
    return (ObjectStorageClientOptions) super.setUseAlpn(useAlpn);
  }

  @Override
  public ObjectStorageClientOptions setSslEngineOptions(SSLEngineOptions sslEngineOptions) {
    return (ObjectStorageClientOptions) super.setSslEngineOptions(sslEngineOptions);
  }

  @Override
  public ObjectStorageClientOptions setJdkSslEngineOptions(JdkSSLEngineOptions sslEngineOptions) {
    return (ObjectStorageClientOptions) super.setJdkSslEngineOptions(sslEngineOptions);
  }

  @Override
  public ObjectStorageClientOptions setOpenSslEngineOptions(OpenSSLEngineOptions sslEngineOptions) {
    return (ObjectStorageClientOptions) super.setOpenSslEngineOptions(sslEngineOptions);
  }

  @Override
  public ObjectStorageClientOptions setHttp2ClearTextUpgrade(boolean value) {
    return (ObjectStorageClientOptions) super.setHttp2ClearTextUpgrade(value);
  }

  @Override
  public ObjectStorageClientOptions setAlpnVersions(List<HttpVersion> alpnVersions) {
    return (ObjectStorageClientOptions) super.setAlpnVersions(alpnVersions);
  }

  @Override
  public ObjectStorageClientOptions setMetricsName(String metricsName) {
    return (ObjectStorageClientOptions) super.setMetricsName(metricsName);
  }

  @Override
  public ObjectStorageClientOptions setProxyOptions(ProxyOptions proxyOptions) {
    return (ObjectStorageClientOptions) super.setProxyOptions(proxyOptions);
  }

  @Override
  public ObjectStorageClientOptions setLocalAddress(String localAddress) {
    return (ObjectStorageClientOptions) super.setLocalAddress(localAddress);
  }

  @Override
  public ObjectStorageClientOptions setLogActivity(boolean logEnabled) {
    return (ObjectStorageClientOptions) super.setLogActivity(logEnabled);
  }

  @Override
  public ObjectStorageClientOptions addEnabledSecureTransportProtocol(String protocol) {
    return (ObjectStorageClientOptions) super.addEnabledSecureTransportProtocol(protocol);
  }

  @Override
  public ObjectStorageClientOptions removeEnabledSecureTransportProtocol(String protocol) {
    return (ObjectStorageClientOptions) super.removeEnabledSecureTransportProtocol(protocol);
  }

  @Override
  public ObjectStorageClientOptions setEnabledSecureTransportProtocols(Set<String> enabledSecureTransportProtocols) {
    return (ObjectStorageClientOptions) super.setEnabledSecureTransportProtocols(enabledSecureTransportProtocols);
  }

  @Override
  public ObjectStorageClientOptions setReusePort(boolean reusePort) {
    return (ObjectStorageClientOptions) super.setReusePort(reusePort);
  }

  @Override
  public ObjectStorageClientOptions setTcpFastOpen(boolean tcpFastOpen) {
    return (ObjectStorageClientOptions) super.setTcpFastOpen(tcpFastOpen);
  }

  @Override
  public ObjectStorageClientOptions setTcpCork(boolean tcpCork) {
    return (ObjectStorageClientOptions) super.setTcpCork(tcpCork);
  }

  @Override
  public ObjectStorageClientOptions setTcpQuickAck(boolean tcpQuickAck) {
    return (ObjectStorageClientOptions) super.setTcpQuickAck(tcpQuickAck);
  }

  @Override
  public ObjectStorageClientOptions setHttp2KeepAliveTimeout(int keepAliveTimeout) {
    return (ObjectStorageClientOptions) super.setHttp2KeepAliveTimeout(keepAliveTimeout);
  }

  @Override
  public ObjectStorageClientOptions setForceSni(boolean forceSni) {
    return (ObjectStorageClientOptions) super.setForceSni(forceSni);
  }

  @Override
  public ObjectStorageClientOptions setDecoderInitialBufferSize(int decoderInitialBufferSize) {
    return (ObjectStorageClientOptions) super.setDecoderInitialBufferSize(decoderInitialBufferSize);
  }

  @Override
  public ObjectStorageClientOptions setPoolCleanerPeriod(int poolCleanerPeriod) {
    return (ObjectStorageClientOptions) super.setPoolCleanerPeriod(poolCleanerPeriod);
  }

  @Override
  public ObjectStorageClientOptions setKeepAliveTimeout(int keepAliveTimeout) {
    return (ObjectStorageClientOptions) super.setKeepAliveTimeout(keepAliveTimeout);
  }

  @Override
  public ObjectStorageClientOptions setMaxWebSocketMessageSize(int maxWebsocketMessageSize) {
    return (ObjectStorageClientOptions) super.setMaxWebSocketMessageSize(maxWebsocketMessageSize);
  }

  @Override
  public ObjectStorageClientOptions setMaxInitialLineLength(int maxInitialLineLength) {
    return (ObjectStorageClientOptions) super.setMaxInitialLineLength(maxInitialLineLength);
  }

  @Override
  public ObjectStorageClientOptions setInitialSettings(Http2Settings settings) {
    return (ObjectStorageClientOptions) super.setInitialSettings(settings);
  }

  @Override
  public ObjectStorageClientOptions setSslHandshakeTimeout(long sslHandshakeTimeout) {
    return (ObjectStorageClientOptions) super.setSslHandshakeTimeout(sslHandshakeTimeout);
  }

  @Override
  public ObjectStorageClientOptions setSslHandshakeTimeoutUnit(TimeUnit sslHandshakeTimeoutUnit) {
    return (ObjectStorageClientOptions) super.setSslHandshakeTimeoutUnit(sslHandshakeTimeoutUnit);
  }

  @Override
  public ObjectStorageClientOptions setTryUsePerFrameWebSocketCompression(boolean offer) {
    return (ObjectStorageClientOptions) super.setTryUsePerFrameWebSocketCompression(offer);
  }

  @Override
  public ObjectStorageClientOptions setTryUsePerMessageWebSocketCompression(boolean offer) {
    return (ObjectStorageClientOptions) super.setTryUsePerMessageWebSocketCompression(offer);
  }

  @Override
  public ObjectStorageClientOptions setWebSocketCompressionLevel(int compressionLevel) {
    return (ObjectStorageClientOptions) super.setWebSocketCompressionLevel(compressionLevel);
  }

  @Override
  public ObjectStorageClientOptions setWebSocketCompressionAllowClientNoContext(boolean offer) {
    return (ObjectStorageClientOptions) super.setWebSocketCompressionAllowClientNoContext(offer);
  }

  @Override
  public ObjectStorageClientOptions setWebSocketCompressionRequestServerNoContext(boolean offer) {
    return (ObjectStorageClientOptions) super.setWebSocketCompressionRequestServerNoContext(offer);
  }

  @Override
  public ObjectStorageClientOptions setReadIdleTimeout(int idleTimeout) {
    return (ObjectStorageClientOptions) super.setReadIdleTimeout(idleTimeout);
  }

  @Override
  public ObjectStorageClientOptions setWriteIdleTimeout(int idleTimeout) {
    return (ObjectStorageClientOptions) super.setWriteIdleTimeout(idleTimeout);
  }

  @Override
  public ObjectStorageClientOptions setMaxWebSockets(int maxWebSockets) {
    return (ObjectStorageClientOptions) super.setMaxWebSockets(maxWebSockets);
  }

  @Override
  public ObjectStorageClientOptions setNonProxyHosts(List<String> nonProxyHosts) {
    return (ObjectStorageClientOptions) super.setNonProxyHosts(nonProxyHosts);
  }

  @Override
  public ObjectStorageClientOptions addNonProxyHost(String nonProxyHost) {
    return (ObjectStorageClientOptions) super.addNonProxyHost(nonProxyHost);
  }

  @Override
  public ObjectStorageClientOptions setWebSocketClosingTimeout(int webSocketClosingTimeout) {
    return (ObjectStorageClientOptions) super.setWebSocketClosingTimeout(webSocketClosingTimeout);
  }

  @Override
  public ObjectStorageClientOptions setTracingPolicy(TracingPolicy tracingPolicy) {
    return (ObjectStorageClientOptions) super.setTracingPolicy(tracingPolicy);
  }

  @Override
  public ObjectStorageClientOptions setPoolEventLoopSize(int poolEventLoopSize) {
    return (ObjectStorageClientOptions) super.setPoolEventLoopSize(poolEventLoopSize);
  }

  @Override
  public ObjectStorageClientOptions setShared(boolean shared) {
    return (ObjectStorageClientOptions) super.setShared(shared);
  }

  @Override
  public ObjectStorageClientOptions setName(String name) {
    return (ObjectStorageClientOptions) super.setName(name);
  }
}
