package io.github.emadalblueshi.objectstorage.client.s3;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.objectstorage.container.MinIOS3Container;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@ExtendWith(VertxExtension.class)
@TestMethodOrder(OrderAnnotation.class)
public class S3ClientTest {

  protected static MinIOS3Container container = new MinIOS3Container();
  protected static S3Client s3Client;
  protected static WebClient webClient;
  protected static String HOST;
  protected static Integer PORT_S3_API;
  protected static Integer PORT_WEB_UI;
  protected static String ACCESS_KEY;
  protected static String SECRET_KEY;
  protected static String REGION;
  protected static String BUCKET_NAME = "demo-bucket";

  @BeforeAll
  static void beforeAll(Vertx vertx, VertxTestContext testContext) {

    container.start();
    HOST = container.getHost();
    PORT_S3_API = container.getMappedPort(9000);
    PORT_WEB_UI = container.getMappedPort(9001);
    ACCESS_KEY = container.getAccessKey();
    SECRET_KEY = container.getSecretKey();
    REGION = container.getRegion();

    var s3AuthOptions = new S3AuthOptions()
      .setSignatureVersion(S3SignatureVersion.V4)
      .setAccessKey(ACCESS_KEY)
      .setSecretKey(SECRET_KEY);

    var options = new S3ClientOptions()
      .setAuthOptions(s3AuthOptions)
      .setRegion(REGION)
      .setWebClientOptions(new WebClientOptions()
        .setDefaultHost(HOST)
        .setDefaultPort(PORT_S3_API)
        .setLogActivity(true));

    s3Client = S3Client.create(vertx, options);

    // Test anonymous get objects policy
    webClient = WebClient.create(vertx, options.getWebClientOptions());

    testContext.completeNow();
  }

  @Order(1)
  @Test
  void testPutObjectBucket(VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    String path = "/" + BUCKET_NAME;
    S3BucketOptions options = new S3BucketOptions();
    s3Client.putBucket(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertNotNull(r.requestId());
        checkpoint.flag();
        webClient.get(path).send()
          .onComplete(testContext.succeeding(wr -> testContext.verify(() -> {
            assertEquals("application/xml", wr.getHeader("content-type"));
            assertEquals(403, wr.statusCode());
            checkpoint.flag();
          })));
      })));
  }

  @Order(2)
  @Test
  void testGetObjectBucketPolicyStatus(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME;
    S3BucketOptions options = new S3BucketOptions();
    s3Client.getBucketPolicyStatus(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("application/xml", r.contentType());
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.requestId());
        assertFalse(r.body().isPublic());
        testContext.completeNow();
      })));
  }

  @Order(2)
  @Test
  void testFailedGetObjectBucketPolicyStatus(Vertx vertx, VertxTestContext testContext) {
    String notExistBucket = "/demo-bucket-abc";
    S3BucketOptions options = new S3BucketOptions();
    s3Client.getBucketPolicyStatus(options, notExistBucket)
      .onComplete(testContext.failing(exception -> testContext.verify(() -> {
        S3ResponseException s3Exception = (S3ResponseException) exception;
        assertInstanceOf(S3ResponseException.class, s3Exception);
        assertEquals("NoSuchBucket", s3Exception.getCode());
        assertEquals(notExistBucket, s3Exception.getResource());
        assertEquals("The specified bucket does not exist", s3Exception.getMessage());
        assertNotNull(s3Exception.getRequestId());
        testContext.completeNow();
      })));
  }

  @Order(3)
  @Test
  void testPutObjectBucketPolicy(Vertx vertx, VertxTestContext testContext) {
    var policy = vertx.fileSystem().readFileBlocking("allow-public-get-object-policy.json").toJsonObject();
    String path = "/" + BUCKET_NAME;
    S3BucketOptions options = new S3BucketOptions();
    s3Client.putBucketPolicy(options, path, policy)
      .onComplete(testContext.succeeding(res -> testContext.verify(() -> {
        assertNull(res.body());
        assertEquals(204, res.statusCode());
        assertEquals("kw-west-1", res.getHeader("x-amz-bucket-region"));
        assertNotNull(res.getHeader("x-amz-id-2"));
        assertNotNull(res.requestId());
        testContext.completeNow();
      })));
  }

  @Order(4)
  @Test
  void testGetObjectBucketPolicy(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME;
    S3BucketOptions options = new S3BucketOptions();
    s3Client.getBucketPolicy(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("application/json", r.contentType());
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.requestId());
        assertNotNull(r.body());
        // Need test asserts for the body
        // System.out.println(r.bodyAsJsonObject().encodePrettily());
        testContext.completeNow();
      })));
  }

  @Order(5)
  @Test
  void testPutObjectObject(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    String path = "/" + BUCKET_NAME + "/cool.txt";
    Buffer buffer = vertx.fileSystem().readFileBlocking("fun.txt");
    S3ObjectOptions options = new S3ObjectOptions()
      .contentType("text/plain");
    s3Client.putObject(options, path, buffer)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.requestId());
        assertNotNull(r.ETag());
        checkpoint.flag();
        webClient.get(path).send()
          .onComplete(testContext.succeeding(wr -> testContext.verify(() -> {
            assertEquals(200, wr.statusCode());
            assertEquals("text/plain", wr.getHeader("content-type"));
            assertNotNull(wr.getHeader("etag"));
            assertNotNull(wr.getHeader("x-amz-id-2"));
            assertNotNull(wr.getHeader("x-amz-request-id"));
            assertNotNull(wr.body());
            checkpoint.flag();
          })));
      })));
  }

  @Order(6)
  @Test
  void testCopyObjectObjectSucceeded(VertxTestContext testContext) {
    String source = "/" + BUCKET_NAME + "/cool.txt";
    String target = "/" + BUCKET_NAME + "/cool2.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.copyObject(options, source, target)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.requestId());
        assertNotNull(r.body());
        assertNotNull(r.body().getETag());
        assertNotNull(r.body().getLastModified());
        testContext.completeNow();
      })));
  }

  @Order(7)
  @Test
  void testGetObjectObjectSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.getObject(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.requestId());
        assertEquals("text/plain", r.contentType());
        assertEquals("Hi!", r.bodyAsString());
        testContext.completeNow();
      })));
  }

  @Order(7)
  @Test
  void testGetObjectObjectFailed(VertxTestContext testContext) {
    String notExistKey = "/" + BUCKET_NAME + "/not-exist-object.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.getObject(options, notExistKey)
      .onComplete(testContext.failing(exception -> testContext.verify(() -> {
        S3ResponseException s3Exception = (S3ResponseException) exception;
        assertInstanceOf(S3ResponseException.class, s3Exception);
        assertEquals("NoSuchKey", s3Exception.getCode());
        assertEquals(notExistKey, s3Exception.getResource());
        assertEquals("The specified key does not exist.", s3Exception.getMessage());
        assertNotNull(s3Exception.getRequestId());
        testContext.completeNow();
      })));
  }

  @Order(8)
  @Test
  void testObjectObjectInfoSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.ObjectInfo(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("text/plain", r.contentType());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        testContext.completeNow();
      })));
  }

  @Order(9)
  @Test
  void testObjectObjectAclSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.ObjectAcl(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertNotNull(r.body());
        S3AccessControlPolicy acp = r.body();
        assertNotNull(acp);
        S3AccessControlList acl = acp.getAccessControlList();
        assertNotNull(acl);
        List<S3Grant> s3GrantList = acl.getGrantList();
        assertNotNull(s3GrantList);
        assertEquals(1, s3GrantList.size());
        assertEquals("FULL_CONTROL", s3GrantList.get(0).getPermission());
        assertEquals("CanonicalUser", s3GrantList.get(0).getGrantee().getType());
        assertEquals("CanonicalUser", s3GrantList.get(0).getGrantee().getAttributeType());
        testContext.completeNow();
      })));
  }

  @Order(9)
  @Test
  void testObjectObjectAclFailed(VertxTestContext testContext) {
    String notExistKey = "/" + BUCKET_NAME + "/cool.txtttt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.ObjectAcl(options, notExistKey)
      .onComplete(testContext.failing(exception -> testContext.verify(() -> {
        S3ResponseException s3Exception = (S3ResponseException) exception;
        assertInstanceOf(S3ResponseException.class, s3Exception);
        assertEquals("NoSuchKey", s3Exception.getCode());
        assertEquals(notExistKey, s3Exception.getResource());
        assertEquals("The specified key does not exist.", s3Exception.getMessage());
        assertNotNull(s3Exception.getRequestId());
        testContext.completeNow();
      })));
  }

  @Order(10)
  @Test
  void testDeleteObjectObjectSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.deleteObject(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(204, r.statusCode());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        testContext.completeNow();
      })));
  }

  @Order(11)
  @Test
  void testDeleteObjectCopiedObject(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool2.txt";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.deleteObject(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(204, r.statusCode());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        testContext.completeNow();
      })));
  }

  @Order(12)
  @Test
  void testDeleteObjectBucketPolicy(VertxTestContext testContext) {
    String path = "/demo-bucket";
    S3BucketOptions options = new S3BucketOptions();
    s3Client.deleteBucketPolicy(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(204, r.statusCode());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        testContext.completeNow();
      })));
  }

  @Order(13)
  @Test
  void testPutObjectFileAsStream(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/large-image.png";
    String filePath = "large-image.png";
    S3ObjectOptions options = new S3ObjectOptions().contentType("image/png");
    s3Client.putObjectFileAsStream(options, path, filePath)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("application/xml", r.contentType());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.body());
        assertNotNull(r.body().getETag());
        assertEquals(BUCKET_NAME, r.body().getBucket());
        assertEquals("http://localhost/demo-bucket/large-image.png", r.body().getLocation());
        assertEquals("large-image.png", r.body().getKey());
        testContext.completeNow();
      })));
  }

  @Order(13)
  @Test
  void testPutObjectAsStream(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/large-stream-image.png";
    AsyncFile asyncFile = vertx.fileSystem().openBlocking("large-image.png", new OpenOptions().setRead(true));
    S3ObjectOptions options = new S3ObjectOptions().contentType("image/png");
    s3Client.putObjectAsStream(options, path, asyncFile)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertEquals("application/xml", r.contentType());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        assertNotNull(r.body());
        assertNotNull(r.body().getETag());
        assertEquals(BUCKET_NAME, r.body().getBucket());
        assertEquals("http://localhost/demo-bucket/large-stream-image.png", r.body().getLocation());
        assertEquals("large-stream-image.png", r.body().getKey());
        testContext.completeNow();
      })));
  }

  @Order(14)
  @Test
  void testGetObjectUpload(Vertx vertx, VertxTestContext testContext) {
    int size = vertx.fileSystem().readFileBlocking("large-image.png").length();
    String path = "/" + BUCKET_NAME + "/large-image.png";
    S3ObjectOptions options = new S3ObjectOptions();
    s3Client.getObject(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(200, r.statusCode());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.bucketRegion());
        assertEquals(String.valueOf(size), r.contentLength());
        assertNotNull(r.requestId());
        assertEquals("image/png", r.contentType());
        testContext.completeNow();
      })));
  }

  @Disabled
  @Order(15)
  @Test
  void testDeleteObjectBucket(VertxTestContext testContext) {
    String path = "/demo-bucket";
    S3BucketOptions options = new S3BucketOptions();
    s3Client.deleteBucket(options, path)
      .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
        assertEquals(204, r.statusCode());
        assertNotNull(r.requestId());
        assertNotNull(r.getHeader("x-amz-id-2"));
        assertNotNull(r.getHeader("x-amz-request-id"));
        assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
        testContext.completeNow();
      })));
  }

  @AfterAll
  static void afterAll() {
    s3Client.close();
    webClient.close();
    container.stop();
  }

}
