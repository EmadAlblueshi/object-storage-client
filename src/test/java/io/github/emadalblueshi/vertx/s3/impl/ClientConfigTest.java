package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.vertx.core.json.JsonObject;

/** Validation in {@code S3Client.create}: every invalid option fails fast with IllegalArgumentException. */
class ClientConfigTest {

  @Test
  void validHttpsEndpointWithDefaultPort() {
    ClientConfig config = ClientConfig.from(valid().setEndpoint("https://nyc3.digitaloceanspaces.com"));
    assertTrue(config.tls);
    assertEquals("nyc3.digitaloceanspaces.com", config.host);
    assertEquals(443, config.port);
    assertTrue(config.defaultPort);
    assertEquals("nyc3", config.region);
  }

  @Test
  void validHttpEndpointWithExplicitPort() {
    ClientConfig config = ClientConfig.from(valid().setEndpoint("http://localhost:9000/"));
    assertFalse(config.tls);
    assertEquals(9000, config.port);
    assertFalse(config.defaultPort);
    assertEquals("localhost", config.httpClientOptions.getDefaultHost());
    assertEquals(9000, config.httpClientOptions.getDefaultPort());
    assertFalse(config.httpClientOptions.isSsl());
  }

  @Test
  void explicitDefaultPortIsTreatedAsDefault() {
    assertTrue(ClientConfig.from(valid().setEndpoint("https://s3.example.com:443")).defaultPort);
    assertTrue(ClientConfig.from(valid().setEndpoint("HTTP://s3.example.com:80")).defaultPort);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "", "  ", "s3.example.com", "ftp://s3.example.com", "https://", "https://user:pw@s3.example.com",
    "https://s3.example.com/prefix", "https://s3.example.com?x=1", "https://s3.example.com#f", "https://bad host",
    "https://under_score:9000"
  })
  void invalidEndpointsAreRejected(String endpoint) {
    assertInvalid(o -> o.setEndpoint(endpoint));
  }

  @Test
  void missingEndpointIsRejected() {
    assertInvalid(o -> o.setEndpoint(null));
  }

  @Test
  void regionIsRequired() {
    assertInvalid(o -> o.setRegion(null));
    assertInvalid(o -> o.setRegion(" "));
  }

  @Test
  void optionsFromJsonAloneAreValid() {
    ClientConfig config = ClientConfig.from(new S3ClientOptions(new JsonObject()
      .put("endpoint", "https://nyc3.digitaloceanspaces.com")
      .put("region", "nyc3")
      .put("credentials", new JsonObject().put("accessKey", "AK").put("secretKey", "SK").put("sessionToken", "ST"))));
    assertEquals("AK", config.credentials.getAccessKey());
    assertEquals("SK", config.credentials.getSecretKey());
    assertEquals("ST", config.credentials.getSessionToken());
  }

  @Test
  void credentialsAreRequired() {
    assertInvalid(o -> o.setCredentials(null));
  }

  @Test
  void staticCredentialsNeedBothKeys() {
    assertInvalid(o -> o.setCredentials(new S3Credentials(null, "secret")));
    assertInvalid(o -> o.setCredentials(new S3Credentials("access", " ")));
  }

  @Test
  void staticCredentialsAreSnapshotted() {
    S3Credentials credentials = new S3Credentials("access", "secret");
    ClientConfig config = ClientConfig.from(valid().setCredentials(credentials));
    credentials.setSecretKey("changed");
    assertEquals("secret", config.credentials.getSecretKey());
    assertNotSame(credentials, config.credentials);
  }

  @Test
  void transportOptionsMustNotSetHostPortOrSsl() {
    assertInvalid(o -> o.getHttpClientOptions().setDefaultHost("other"));
    assertInvalid(o -> o.getHttpClientOptions().setDefaultPort(9000));
    assertInvalid(o -> o.getHttpClientOptions().setSsl(true));
  }

  @ParameterizedTest
  @ValueSource(strings = { "http://localhost:9000", "http://127.0.0.1:9000", "http://[::1]:9000" })
  void virtualHostedStyleNeedsADnsName(String endpoint) {
    assertInvalid(o -> o.setEndpoint(endpoint).setPathStyleAccess(false));
  }

  @Test
  void addressing() {
    ClientConfig pathStyle = ClientConfig.from(valid());
    assertEquals("nyc3.digitaloceanspaces.com", pathStyle.hostFor("bucket"));
    assertEquals("/bucket/dir/key.txt", pathStyle.pathFor("bucket", "dir/key.txt"));
    assertEquals("/bucket", pathStyle.pathFor("bucket", null));
    assertEquals("/", pathStyle.pathFor(null, null));

    ClientConfig virtual = ClientConfig.from(valid().setPathStyleAccess(false));
    assertEquals("bucket.nyc3.digitaloceanspaces.com", virtual.hostFor("bucket"));
    assertEquals("nyc3.digitaloceanspaces.com", virtual.hostFor(null));
    assertEquals("/dir/key.txt", virtual.pathFor("bucket", "dir/key.txt"));
    assertEquals("/", virtual.pathFor("bucket", null));
  }

  @Test
  void optionsAreCopied() {
    S3ClientOptions options = valid();
    ClientConfig config = ClientConfig.from(options);
    assertNotSame(options.getHttpClientOptions(), config.httpClientOptions);
    assertNotSame(options.getPoolOptions(), config.poolOptions);
    options.getHttpClientOptions().setConnectTimeout(1);
    assertEquals(new io.vertx.core.http.HttpClientOptions().getConnectTimeout(), config.httpClientOptions.getConnectTimeout());
  }

  @Test
  void nullOptionsAreRejected() {
    assertThrows(NullPointerException.class, () -> ClientConfig.from(null));
  }

  private static S3ClientOptions valid() {
    return new S3ClientOptions()
      .setEndpoint("https://nyc3.digitaloceanspaces.com")
      .setRegion("nyc3")
      .setCredentials(new S3Credentials("access", "secret"));
  }

  private static void assertInvalid(Consumer<S3ClientOptions> change) {
    S3ClientOptions options = valid();
    change.accept(options);
    assertThrows(IllegalArgumentException.class, () -> ClientConfig.from(options), options::toString);
  }
}
