package io.github.emadalblueshi.vertx.s3.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;

class BucketNamesTest {

  private static final ClientConfig PATH_STYLE_HTTPS = config("https://s3.example.com", true);
  private static final ClientConfig VIRTUAL_HTTPS = config("https://s3.example.com", false);
  private static final ClientConfig VIRTUAL_HTTP = config("http://s3.example.com", false);

  @ParameterizedTest
  @ValueSource(strings = {"abc", "my-bucket", "my.bucket.v2", "0bucket9", "a-b-c", "x2.y-z",
    "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz01234567890"})
  void validNames(String bucket) {
    assertNull(BucketNames.problem(bucket, PATH_STYLE_HTTPS));
  }

  @Test
  void sixtyThreeCharactersIsTheMaximum() {
    assertEquals(63, "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz01234567890".length());
    assertTrue(BucketNames.problem("a" + "b".repeat(62) + "c", PATH_STYLE_HTTPS).contains("3 to 63"));
  }

  @Test
  void nullIsRejected() {
    assertEquals("The bucket name is required", BucketNames.problem(null, PATH_STYLE_HTTPS));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a", "ab"})
  void tooShort(String bucket) {
    assertProblem(bucket, "must be 3 to 63 characters long");
  }

  @ParameterizedTest
  @ValueSource(strings = {"My-Bucket", "my_bucket", "my bucket", "-bucket", "bucket-", ".bucket", "bucket.",
    "bück", "bucket/key", "bucket%2F"})
  void invalidCharactersOrEnds(String bucket) {
    assertProblem(bucket, "must contain only lowercase letters, digits, dots and hyphens");
  }

  @Test
  void adjacentDots() {
    assertProblem("my..bucket", "must not contain two adjacent dots");
  }

  @ParameterizedTest
  @ValueSource(strings = {"192.168.5.4", "10.0.0.1"})
  void ipAddress(String bucket) {
    assertProblem(bucket, "must not be formatted as an IP address");
  }

  @Test
  void dottedNamesAreRejectedOnlyForVirtualHostedStyleOverHttps() {
    String problem = BucketNames.problem("my.bucket", VIRTUAL_HTTPS);
    assertNotNull(problem);
    assertTrue(problem.contains("use pathStyleAccess = true"), problem);
    assertNull(BucketNames.problem("my.bucket", PATH_STYLE_HTTPS));
    assertNull(BucketNames.problem("my.bucket", VIRTUAL_HTTP));
    assertNull(BucketNames.problem("my-bucket", VIRTUAL_HTTPS));
  }

  private static void assertProblem(String bucket, String reason) {
    String problem = BucketNames.problem(bucket, PATH_STYLE_HTTPS);
    assertNotNull(problem, bucket);
    assertTrue(problem.startsWith("Invalid bucket name \"" + bucket + "\": " + reason), problem);
  }

  private static ClientConfig config(String endpoint, boolean pathStyle) {
    return ClientConfig.from(new S3ClientOptions()
      .setEndpoint(endpoint)
      .setRegion("kw-west-1")
      .setPathStyleAccess(pathStyle)
      .setCredentials(new S3Credentials("access", "secret")));
  }
}
