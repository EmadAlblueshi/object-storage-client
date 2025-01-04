package io.github.emadalblueshi.objectstorage.client.s3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.emadalblueshi.objectstorage.client.ObjectStorageClientOptions;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.json.annotations.JsonGen;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
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
import io.vertx.uritemplate.ExpandOptions;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@DataObject
@JsonGen(publicConverter = false)
public class S3ClientOptions extends ObjectStorageClientOptions {

  private static final String DEFAULT_REGION = "default";
  private static final String DEFAULT_SERVICE = "s3";

  private String region;
  private String service;

  private S3AuthOptions authOptions;

  public S3ClientOptions() {
    super();
    setRegion(DEFAULT_REGION);
    setService(DEFAULT_SERVICE);
    setAuthOptions(new S3AuthOptions());
  }

  public S3ClientOptions(S3ClientOptions other) {
    super(other);
    setRegion(other.getRegion());
    setService(other.getService());
    setAuthOptions(other.getAuthOptions());
  }

  public S3ClientOptions(JsonObject json) {
    super(json);
    S3ClientOptionsConverter.fromJson(json, this);
  }

  public S3ClientOptions setRegion(String region) {
    this.region = region;
    return this;
  }

  public String getRegion() {
    return region;
  }

  public S3ClientOptions setService(String service) {
    this.service = service;
    return this;
  }

  public String getService() {
    return service;
  }

  public S3ClientOptions setAuthOptions(S3AuthOptions s3AuthOptions) {
    this.authOptions = s3AuthOptions;
    return this;
  }

  public S3AuthOptions getAuthOptions() {
    return authOptions;
  }

  @GenIgnore
  public S3ClientOptions setHost(String host) {
    return (S3ClientOptions) super.setDefaultHost(host);
  }

  @GenIgnore
  public String getHost() {
    return super.getDefaultHost();
  }

  @GenIgnore
  public S3ClientOptions setPort(int port) {
    return (S3ClientOptions) super.setDefaultPort(port);
  }

  @GenIgnore
  public int getPort() {
    return super.getDefaultPort();
  }

  @Override
  public S3ClientOptions setUserAgentEnabled(boolean userAgentEnabled) {
    return (S3ClientOptions) super.setUserAgentEnabled(userAgentEnabled);
  }

  @Override
  public S3ClientOptions setUserAgent(String userAgent) {
    return (S3ClientOptions) super.setUserAgent(userAgent);
  }

  @Override
  public S3ClientOptions setFollowRedirects(boolean followRedirects) {
    return (S3ClientOptions) super.setFollowRedirects(followRedirects);
  }

  @Override
  public S3ClientOptions setTemplateExpandOptions(ExpandOptions templateExpandOptions) {
    return (S3ClientOptions) super.setTemplateExpandOptions(templateExpandOptions);
  }

  @Override
  public S3ClientOptions setMaxRedirects(int maxRedirects) {
    return (S3ClientOptions) super.setMaxRedirects(maxRedirects);
  }

  @Override
  public S3ClientOptions setSendBufferSize(int sendBufferSize) {
    return (S3ClientOptions) super.setSendBufferSize(sendBufferSize);
  }

  @Override
  public S3ClientOptions setReceiveBufferSize(int receiveBufferSize) {
    return (S3ClientOptions) super.setReceiveBufferSize(receiveBufferSize);
  }

  @Override
  public S3ClientOptions setReuseAddress(boolean reuseAddress) {
    return (S3ClientOptions) super.setReuseAddress(reuseAddress);
  }

  @Override
  public S3ClientOptions setTrafficClass(int trafficClass) {
    return (S3ClientOptions) super.setTrafficClass(trafficClass);
  }

  @Override
  public S3ClientOptions setTcpNoDelay(boolean tcpNoDelay) {
    return (S3ClientOptions) super.setTcpNoDelay(tcpNoDelay);
  }

  @Override
  public S3ClientOptions setTcpKeepAlive(boolean tcpKeepAlive) {
    return (S3ClientOptions) super.setTcpKeepAlive(tcpKeepAlive);
  }

  @Override
  public S3ClientOptions setSoLinger(int soLinger) {
    return (S3ClientOptions) super.setSoLinger(soLinger);
  }

  @Override
  public S3ClientOptions setIdleTimeout(int idleTimeout) {
    return (S3ClientOptions) super.setIdleTimeout(idleTimeout);
  }

  @Override
  public S3ClientOptions setIdleTimeoutUnit(TimeUnit idleTimeoutUnit) {
    return (S3ClientOptions) super.setIdleTimeoutUnit(idleTimeoutUnit);
  }

  @Override
  public S3ClientOptions setSsl(boolean ssl) {
    return (S3ClientOptions) super.setSsl(ssl);
  }

  @Override
  public S3ClientOptions setKeyCertOptions(KeyCertOptions options) {
    return (S3ClientOptions) super.setKeyCertOptions(options);
  }

  @Override
  public S3ClientOptions setKeyStoreOptions(JksOptions options) {
    return (S3ClientOptions) super.setKeyStoreOptions(options);
  }

  @Override
  public S3ClientOptions setPfxKeyCertOptions(PfxOptions options) {
    return (S3ClientOptions) super.setPfxKeyCertOptions(options);
  }

  @Override
  public S3ClientOptions setTrustOptions(TrustOptions options) {
    return (S3ClientOptions) super.setTrustOptions(options);
  }

  @Override
  public S3ClientOptions setPemKeyCertOptions(PemKeyCertOptions options) {
    return (S3ClientOptions) super.setPemKeyCertOptions(options);
  }

  @Override
  public S3ClientOptions setTrustStoreOptions(JksOptions options) {
    return (S3ClientOptions) super.setTrustStoreOptions(options);
  }

  @Override
  public S3ClientOptions setPfxTrustOptions(PfxOptions options) {
    return (S3ClientOptions) super.setPfxTrustOptions(options);
  }

  @Override
  public S3ClientOptions setPemTrustOptions(PemTrustOptions options) {
    return (S3ClientOptions) super.setPemTrustOptions(options);
  }

  @Override
  public S3ClientOptions addEnabledCipherSuite(String suite) {
    return (S3ClientOptions) super.addEnabledCipherSuite(suite);
  }

  @Override
  public S3ClientOptions addCrlPath(String crlPath) throws NullPointerException {
    return (S3ClientOptions) super.addCrlPath(crlPath);
  }

  @Override
  public S3ClientOptions addCrlValue(Buffer crlValue) throws NullPointerException {
    return (S3ClientOptions) super.addCrlValue(crlValue);
  }

  @Override
  public S3ClientOptions setConnectTimeout(int connectTimeout) {
    return (S3ClientOptions) super.setConnectTimeout(connectTimeout);
  }

  @Override
  public S3ClientOptions setTrustAll(boolean trustAll) {
    return (S3ClientOptions) super.setTrustAll(trustAll);
  }

  @Override
  public S3ClientOptions setMaxPoolSize(int maxPoolSize) {
    return (S3ClientOptions) super.setMaxPoolSize(maxPoolSize);
  }

  @Override
  public S3ClientOptions setHttp2MultiplexingLimit(int limit) {
    return (S3ClientOptions) super.setHttp2MultiplexingLimit(limit);
  }

  @Override
  public S3ClientOptions setHttp2MaxPoolSize(int max) {
    return (S3ClientOptions) super.setHttp2MaxPoolSize(max);
  }

  @Override
  public S3ClientOptions setHttp2ConnectionWindowSize(int http2ConnectionWindowSize) {
    return (S3ClientOptions) super.setHttp2ConnectionWindowSize(http2ConnectionWindowSize);
  }

  @Override
  public S3ClientOptions setKeepAlive(boolean keepAlive) {
    return (S3ClientOptions) super.setKeepAlive(keepAlive);
  }

  @Override
  public S3ClientOptions setPipelining(boolean pipelining) {
    return (S3ClientOptions) super.setPipelining(pipelining);
  }

  @Override
  public S3ClientOptions setPipeliningLimit(int limit) {
    return (S3ClientOptions) super.setPipeliningLimit(limit);
  }

  @Override
  public S3ClientOptions setVerifyHost(boolean verifyHost) {
    return (S3ClientOptions) super.setVerifyHost(verifyHost);
  }

  @Override
  @Deprecated
  public S3ClientOptions setTryUseCompression(boolean tryUseCompression) {
    return (S3ClientOptions) super.setTryUseCompression(tryUseCompression);
  }

  @Override
  public S3ClientOptions setDecompressionSupported(boolean tryUseCompression) {
    return (S3ClientOptions) super.setDecompressionSupported(tryUseCompression);
  }

  @Override
  public S3ClientOptions setSendUnmaskedFrames(boolean sendUnmaskedFrames) {
    return (S3ClientOptions) super.setSendUnmaskedFrames(sendUnmaskedFrames);
  }

  @Override
  public S3ClientOptions setMaxWebSocketFrameSize(int maxWebsocketFrameSize) {
    return (S3ClientOptions) super.setMaxWebSocketFrameSize(maxWebsocketFrameSize);
  }

  @Override
  public S3ClientOptions setDefaultHost(String defaultHost) {
    return (S3ClientOptions) super.setDefaultHost(defaultHost);
  }

  @Override
  public S3ClientOptions setDefaultPort(int defaultPort) {
    return (S3ClientOptions) super.setDefaultPort(defaultPort);
  }

  @Override
  public S3ClientOptions setMaxChunkSize(int maxChunkSize) {
    return (S3ClientOptions) super.setMaxChunkSize(maxChunkSize);
  }

  @Override
  public S3ClientOptions setProtocolVersion(HttpVersion protocolVersion) {
    return (S3ClientOptions) super.setProtocolVersion(protocolVersion);
  }

  @Override
  public S3ClientOptions setMaxHeaderSize(int maxHeaderSize) {
    return (S3ClientOptions) super.setMaxHeaderSize(maxHeaderSize);
  }

  @Override
  public S3ClientOptions setMaxWaitQueueSize(int maxWaitQueueSize) {
    return (S3ClientOptions) super.setMaxWaitQueueSize(maxWaitQueueSize);
  }

  @Override
  public S3ClientOptions setUseAlpn(boolean useAlpn) {
    return (S3ClientOptions) super.setUseAlpn(useAlpn);
  }

  @Override
  public S3ClientOptions setSslEngineOptions(SSLEngineOptions sslEngineOptions) {
    return (S3ClientOptions) super.setSslEngineOptions(sslEngineOptions);
  }

  @Override
  public S3ClientOptions setJdkSslEngineOptions(JdkSSLEngineOptions sslEngineOptions) {
    return (S3ClientOptions) super.setJdkSslEngineOptions(sslEngineOptions);
  }

  @Override
  public S3ClientOptions setOpenSslEngineOptions(OpenSSLEngineOptions sslEngineOptions) {
    return (S3ClientOptions) super.setOpenSslEngineOptions(sslEngineOptions);
  }

  @Override
  public S3ClientOptions setHttp2ClearTextUpgrade(boolean value) {
    return (S3ClientOptions) super.setHttp2ClearTextUpgrade(value);
  }

  @Override
  public S3ClientOptions setAlpnVersions(List<HttpVersion> alpnVersions) {
    return (S3ClientOptions) super.setAlpnVersions(alpnVersions);
  }

  @Override
  public S3ClientOptions setMetricsName(String metricsName) {
    return (S3ClientOptions) super.setMetricsName(metricsName);
  }

  @Override
  public S3ClientOptions setProxyOptions(ProxyOptions proxyOptions) {
    return (S3ClientOptions) super.setProxyOptions(proxyOptions);
  }

  @Override
  public S3ClientOptions setLocalAddress(String localAddress) {
    return (S3ClientOptions) super.setLocalAddress(localAddress);
  }

  @Override
  public S3ClientOptions setLogActivity(boolean logEnabled) {
    return (S3ClientOptions) super.setLogActivity(logEnabled);
  }

  @Override
  public S3ClientOptions addEnabledSecureTransportProtocol(String protocol) {
    return (S3ClientOptions) super.addEnabledSecureTransportProtocol(protocol);
  }

  @Override
  public S3ClientOptions removeEnabledSecureTransportProtocol(String protocol) {
    return (S3ClientOptions) super.removeEnabledSecureTransportProtocol(protocol);
  }

  @Override
  public S3ClientOptions setEnabledSecureTransportProtocols(Set<String> enabledSecureTransportProtocols) {
    return (S3ClientOptions) super.setEnabledSecureTransportProtocols(enabledSecureTransportProtocols);
  }

  @Override
  public S3ClientOptions setReusePort(boolean reusePort) {
    return (S3ClientOptions) super.setReusePort(reusePort);
  }

  @Override
  public S3ClientOptions setTcpFastOpen(boolean tcpFastOpen) {
    return (S3ClientOptions) super.setTcpFastOpen(tcpFastOpen);
  }

  @Override
  public S3ClientOptions setTcpCork(boolean tcpCork) {
    return (S3ClientOptions) super.setTcpCork(tcpCork);
  }

  @Override
  public S3ClientOptions setTcpQuickAck(boolean tcpQuickAck) {
    return (S3ClientOptions) super.setTcpQuickAck(tcpQuickAck);
  }

  @Override
  public S3ClientOptions setHttp2KeepAliveTimeout(int keepAliveTimeout) {
    return (S3ClientOptions) super.setHttp2KeepAliveTimeout(keepAliveTimeout);
  }

  @Override
  public S3ClientOptions setForceSni(boolean forceSni) {
    return (S3ClientOptions) super.setForceSni(forceSni);
  }

  @Override
  public S3ClientOptions setDecoderInitialBufferSize(int decoderInitialBufferSize) {
    return (S3ClientOptions) super.setDecoderInitialBufferSize(decoderInitialBufferSize);
  }

  @Override
  public S3ClientOptions setPoolCleanerPeriod(int poolCleanerPeriod) {
    return (S3ClientOptions) super.setPoolCleanerPeriod(poolCleanerPeriod);
  }

  @Override
  public S3ClientOptions setKeepAliveTimeout(int keepAliveTimeout) {
    return (S3ClientOptions) super.setKeepAliveTimeout(keepAliveTimeout);
  }

  @Override
  public S3ClientOptions setMaxWebSocketMessageSize(int maxWebsocketMessageSize) {
    return (S3ClientOptions) super.setMaxWebSocketMessageSize(maxWebsocketMessageSize);
  }

  @Override
  public S3ClientOptions setMaxInitialLineLength(int maxInitialLineLength) {
    return (S3ClientOptions) super.setMaxInitialLineLength(maxInitialLineLength);
  }

  @Override
  public S3ClientOptions setInitialSettings(Http2Settings settings) {
    return (S3ClientOptions) super.setInitialSettings(settings);
  }

  @Override
  public S3ClientOptions setSslHandshakeTimeout(long sslHandshakeTimeout) {
    return (S3ClientOptions) super.setSslHandshakeTimeout(sslHandshakeTimeout);
  }

  @Override
  public S3ClientOptions setSslHandshakeTimeoutUnit(TimeUnit sslHandshakeTimeoutUnit) {
    return (S3ClientOptions) super.setSslHandshakeTimeoutUnit(sslHandshakeTimeoutUnit);
  }

  @Override
  public S3ClientOptions setTryUsePerFrameWebSocketCompression(boolean offer) {
    return (S3ClientOptions) super.setTryUsePerFrameWebSocketCompression(offer);
  }

  @Override
  public S3ClientOptions setTryUsePerMessageWebSocketCompression(boolean offer) {
    return (S3ClientOptions) super.setTryUsePerMessageWebSocketCompression(offer);
  }

  @Override
  public S3ClientOptions setWebSocketCompressionLevel(int compressionLevel) {
    return (S3ClientOptions) super.setWebSocketCompressionLevel(compressionLevel);
  }

  @Override
  public S3ClientOptions setWebSocketCompressionAllowClientNoContext(boolean offer) {
    return (S3ClientOptions) super.setWebSocketCompressionAllowClientNoContext(offer);
  }

  @Override
  public S3ClientOptions setWebSocketCompressionRequestServerNoContext(boolean offer) {
    return (S3ClientOptions) super.setWebSocketCompressionRequestServerNoContext(offer);
  }

  @Override
  public S3ClientOptions setReadIdleTimeout(int idleTimeout) {
    return (S3ClientOptions) super.setReadIdleTimeout(idleTimeout);
  }

  @Override
  public S3ClientOptions setWriteIdleTimeout(int idleTimeout) {
    return (S3ClientOptions) super.setWriteIdleTimeout(idleTimeout);
  }

  @Override
  public S3ClientOptions setMaxWebSockets(int maxWebSockets) {
    return (S3ClientOptions) super.setMaxWebSockets(maxWebSockets);
  }

  @Override
  public S3ClientOptions setNonProxyHosts(List<String> nonProxyHosts) {
    return (S3ClientOptions) super.setNonProxyHosts(nonProxyHosts);
  }

  @Override
  public S3ClientOptions addNonProxyHost(String nonProxyHost) {
    return (S3ClientOptions) super.addNonProxyHost(nonProxyHost);
  }

  @Override
  public S3ClientOptions setWebSocketClosingTimeout(int webSocketClosingTimeout) {
    return (S3ClientOptions) super.setWebSocketClosingTimeout(webSocketClosingTimeout);
  }

  @Override
  public S3ClientOptions setTracingPolicy(TracingPolicy tracingPolicy) {
    return (S3ClientOptions) super.setTracingPolicy(tracingPolicy);
  }

  @Override
  public S3ClientOptions setPoolEventLoopSize(int poolEventLoopSize) {
    return (S3ClientOptions) super.setPoolEventLoopSize(poolEventLoopSize);
  }

  @Override
  public S3ClientOptions setShared(boolean shared) {
    return (S3ClientOptions) super.setShared(shared);
  }

  @Override
  public S3ClientOptions setName(String name) {
    return (S3ClientOptions) super.setName(name);
  }

}
