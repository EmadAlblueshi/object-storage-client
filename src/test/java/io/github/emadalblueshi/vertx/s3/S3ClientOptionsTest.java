package io.github.emadalblueshi.vertx.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ProxyOptions;

class S3ClientOptionsTest {

  private static final String SECRET = "very-secret-value";
  private static final String TOKEN = "session-token-value";

  @Test
  void defaults() {
    S3ClientOptions options = new S3ClientOptions();
    assertNull(options.getEndpoint());
    assertNull(options.getRegion(), "region is required: no magic default");
    assertTrue(options.isPathStyleAccess());
    assertNull(options.getCredentials());
    assertEquals(4 * 1024 * 1024, options.getMaxBufferedObjectSize());
    assertEquals(new HttpClientOptions().toJson(), options.getHttpClientOptions().toJson());
    assertEquals(new PoolOptions().toJson(), options.getPoolOptions().toJson());
  }

  @Test
  void copyConstructorIsDeep() {
    S3ClientOptions original = sample();
    S3ClientOptions copy = new S3ClientOptions(original);

    assertEquals(original.toJson(), copy.toJson());
    assertNotSame(original.getHttpClientOptions(), copy.getHttpClientOptions());
    assertNotSame(original.getPoolOptions(), copy.getPoolOptions());
    assertNotSame(original.getCredentials(), copy.getCredentials());
    assertEquals(SECRET, copy.getCredentials().getSecretKey());

    original.getHttpClientOptions().setConnectTimeout(1);
    original.getPoolOptions().setHttp1MaxSize(1);
    original.getCredentials().setSecretKey("changed");
    assertEquals(4321, copy.getHttpClientOptions().getConnectTimeout());
    assertEquals(42, copy.getPoolOptions().getHttp1MaxSize());
    assertEquals(SECRET, copy.getCredentials().getSecretKey());
  }

  @Test
  void jsonRoundTripOfNonSecretSettings() {
    S3ClientOptions options = sample();
    JsonObject json = options.toJson();
    S3ClientOptions parsed = new S3ClientOptions(json);

    assertEquals(json, parsed.toJson());
    assertEquals("https://nyc3.digitaloceanspaces.com", parsed.getEndpoint());
    assertEquals("nyc3", parsed.getRegion());
    assertFalse(parsed.isPathStyleAccess());
    assertEquals(1024, parsed.getMaxBufferedObjectSize());
    assertEquals(4321, parsed.getHttpClientOptions().getConnectTimeout());
    assertEquals(42, parsed.getPoolOptions().getHttp1MaxSize());
  }

  @Test
  void fromJsonAcceptsSecretsButToJsonNeverWritesThem() {
    JsonObject json = new JsonObject()
      .put("endpoint", "http://localhost:9000")
      .put("region", "test-west-1")
      .put("credentials", new JsonObject().put("accessKey", "AK").put("secretKey", SECRET).put("sessionToken", TOKEN));
    S3ClientOptions options = new S3ClientOptions(json);

    assertEquals(SECRET, options.getCredentials().getSecretKey());
    assertEquals(TOKEN, options.getCredentials().getSessionToken());
    String encoded = options.toJson().encode();
    assertFalse(encoded.contains(SECRET), encoded);
    assertFalse(encoded.contains(TOKEN), encoded);
    assertEquals(new JsonObject().put("accessKey", "AK"), options.toJson().getJsonObject("credentials"));
  }

  @Test
  void fromJsonKeepsDefaultsForAbsentSettings() {
    S3ClientOptions options = new S3ClientOptions(new JsonObject().put("region", "test-west-1"));
    assertTrue(options.isPathStyleAccess());
    assertNotNull(options.getHttpClientOptions());
    assertNotNull(options.getPoolOptions());
  }

  /** The documented configuration path: everything, credentials included, from one JSON object. */
  @Test
  void credentialsAreConfiguredFromJsonLikeEveryOtherOption() {
    JsonObject config = new JsonObject().put("s3", new JsonObject()
      .put("endpoint", "https://nyc3.digitaloceanspaces.com")
      .put("region", "nyc3")
      .put("credentials", new JsonObject().put("accessKey", "AK").put("secretKey", SECRET)));
    S3ClientOptions options = new S3ClientOptions(config.getJsonObject("s3"));
    assertEquals("AK", options.getCredentials().getAccessKey());
    assertEquals(SECRET, options.getCredentials().getSecretKey());
  }

  @Test
  void jsonHasNoProviderKeyOrOtherFunctionalSettings() {
    assertEquals(java.util.Set.of("credentials", "endpoint", "httpClientOptions", "maxBufferedObjectSize",
      "pathStyleAccess", "poolOptions", "publicEndpoint", "region"), sample().toJson().fieldNames());
  }

  @Test
  void publicEndpointDefaultsToNoneAndSurvivesJsonAndCopy() {
    assertNull(new S3ClientOptions().getPublicEndpoint());
    assertEquals("https://files.example.com", new S3ClientOptions(sample().toJson()).getPublicEndpoint());
    assertEquals("https://files.example.com", new S3ClientOptions(sample()).getPublicEndpoint());
  }

  @Test
  void toStringNeverContainsSecrets() {
    String text = sample().toString();
    assertTrue(text.contains("https://nyc3.digitaloceanspaces.com"), text);
    assertTrue(text.contains("nyc3"), text);
    assertTrue(text.contains("publicEndpoint=https://files.example.com"), text);
    assertFalse(text.contains(SECRET), text);
    assertFalse(text.contains(TOKEN), text);
  }

  @Test
  void nestedTransportOptionsKeepTlsSettingsThroughJson() {
    S3ClientOptions options = new S3ClientOptions();
    options.getHttpClientOptions().setTrustOptions(new PemTrustOptions().addCertPath("tls/ca.crt"));
    S3ClientOptions parsed = new S3ClientOptions(options.toJson());
    assertEquals(options.getHttpClientOptions().toJson(), parsed.getHttpClientOptions().toJson());
  }

  @Test
  void toJsonRemovesTransportSecretsThatVertxWritesInClear() {
    S3ClientOptions options = new S3ClientOptions();
    options.getHttpClientOptions()
      .setKeyCertOptions(new JksOptions().setPath("client.jks").setPassword("KEYSTORE-PW").setAliasPassword("ALIAS-PW"))
      .setTrustOptions(new PfxOptions().setPath("trust.p12").setPassword("TRUSTSTORE-PW"))
      .setProxyOptions(new ProxyOptions().setHost("proxy").setUsername("user").setPassword("PROXY-PW"));
    // Precondition: Vert.x writes them in clear.
    assertTrue(options.getHttpClientOptions().toJson().encode().contains("KEYSTORE-PW"));

    String encoded = options.toJson().encode();
    for (String secret : new String[] { "KEYSTORE-PW", "ALIAS-PW", "TRUSTSTORE-PW", "PROXY-PW" }) {
      assertFalse(encoded.contains(secret), () -> secret + " leaked: " + encoded);
    }
    JsonObject http = options.toJson().getJsonObject("httpClientOptions");
    assertEquals("client.jks", http.getJsonObject("keyStoreOptions").getString("path"), "non-secret settings stay");
    assertEquals("user", http.getJsonObject("proxyOptions").getString("username"));
    assertEquals("KEYSTORE-PW", ((JksOptions) options.getHttpClientOptions().getKeyCertOptions()).getPassword(),
      "toJson must not modify the options themselves");
  }

  @Test
  void invalidValuesAreRejectedBySetters() {
    S3ClientOptions options = new S3ClientOptions();
    assertThrows(IllegalArgumentException.class, () -> options.setMaxBufferedObjectSize(0));
    assertThrows(NullPointerException.class, () -> options.setHttpClientOptions(null));
    assertThrows(NullPointerException.class, () -> options.setPoolOptions(null));
  }

  private static S3ClientOptions sample() {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://nyc3.digitaloceanspaces.com")
      .setPublicEndpoint("https://files.example.com")
      .setRegion("nyc3")
      .setPathStyleAccess(false)
      .setCredentials(new S3Credentials("AKIAEXAMPLE", SECRET).setSessionToken(TOKEN))
      .setMaxBufferedObjectSize(1024);
    options.getHttpClientOptions().setConnectTimeout(4321);
    options.getPoolOptions().setHttp1MaxSize(42);
    return options;
  }
}
