package io.github.emadalblueshi.objectstorage.client.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.objectstorage.container.MinIOS3Container;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
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

    // HOST = "localhost";
    // PORT_S3_API = 9000;
    // PORT_WEB_UI = 9001;
    // ACCESS_KEY = "minioadmin";
    // SECRET_KEY = "minioadmin";

    container.start();
    HOST = container.getHost();
    PORT_S3_API = container.getMappedPort(9000);
    PORT_WEB_UI = container.getMappedPort(9001);
    ACCESS_KEY = container.getAccessKey();
    SECRET_KEY = container.getSecretKey();
    REGION = container.getRegion();

    var s3AuthOptions = new S3AuthOptions()
        .setSignatureVersion(SignatureVersion.V4)
        .setAccessKey(ACCESS_KEY)
        .setSecretKey(SECRET_KEY);

    var options = new S3ClientOptions()
        .setLogActivity(true)
        .setAuthOptions(s3AuthOptions)
        .setRegion(REGION)
        .setHost(HOST)
        .setPort(PORT_S3_API);

    s3Client = S3Client.create(vertx, options);

    // Test anonymous get objects policy
    webClient = WebClient.create(vertx, options);

    testContext.completeNow();
  }

  @Order(1)
  @Test
  void testPutBucket(VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
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
  void testGetBucketPolicyStatus(Vertx vertx, VertxTestContext testContext) {
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
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

  @Order(3)
  @Test
  void testPutBucketPolicy(Vertx vertx, VertxTestContext testContext) {
    var policy = vertx.fileSystem().readFileBlocking("policy.json").toJsonObject();
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
    s3Client.putBucketPolicy(options, path, policy)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(204, r.statusCode());
          assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
          assertNotNull(r.getHeader("x-amz-id-2"));
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @Order(4)
  @Test
  void testGetBucketPolicy(VertxTestContext testContext) {
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
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
  void testPutObject(Vertx vertx, VertxTestContext testContext) {
    Checkpoint checkpoint = testContext.checkpoint(2);
    String path = "/" + BUCKET_NAME + "/cool.txt";
    Buffer buffer = vertx.fileSystem().readFileBlocking("fun.txt");
    ObjectOptions options = new ObjectOptions()
        .contentType("text/plain");
    s3Client.put(options, path, buffer)
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
  void testCopyObjectSucceeded(VertxTestContext testContext) {
    String source = "/" + BUCKET_NAME + "/cool.txt";
    String target = "/" + BUCKET_NAME + "/cool2.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.copy(options, source, target)
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
  void testGetObjectSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.get(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.getHeader("x-amz-id-2"));
          assertNotNull(r.getHeader("x-amz-request-id"));
          assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
          assertNotNull(r.requestId());
          assertEquals("text/plain", r.contentType());
          // Ceph radosgw returns the storage only
          // assertEquals("STANDARD", r.storageClass());
          assertEquals("Hi!", r.bodyAsString());
          testContext.completeNow();
        })));
  }

  @Order(8)
  @Test
  void testObjectInfoSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.info(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertEquals("text/plain", r.contentType());
          // Ceph radosgw returns the storage only
          // assertEquals("STANDARD", r.storageClass());
          assertNotNull(r.requestId());
          assertNotNull(r.getHeader("x-amz-id-2"));
          assertNotNull(r.getHeader("x-amz-request-id"));
          assertEquals("kw-west-1", r.getHeader("x-amz-bucket-region"));
          testContext.completeNow();
        })));
  }

  @Order(9)
  @Test
  void testObjectAclSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.acl(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          // Ceph radosgw returns the correct content type only
          // assertEquals("application/xml", r.contentType());
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertNotNull(r.getHeader("x-amz-id-2"));
          assertNotNull(r.getHeader("x-amz-request-id"));
          assertNotNull(r.body());
          testContext.completeNow();
        })));
  }

  @Order(10)
  @Test
  void testDeleteObjectSucceeded(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.delete(options, path)
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
  void testDeleteCopiedObject(VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool2.txt";
    ObjectOptions options = new ObjectOptions();
    s3Client.delete(options, path)
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
  void testDeleteBucketPolicy(VertxTestContext testContext) {
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
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
  void testDeleteBucket(VertxTestContext testContext) {
    String path = "/demo-bucket";
    BucketOptions options = new BucketOptions();
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
