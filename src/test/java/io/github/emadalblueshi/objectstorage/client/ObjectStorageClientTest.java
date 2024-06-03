package io.github.emadalblueshi.objectstorage.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.objectstorage.container.CephRadosGatwayContainer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@TestMethodOrder(OrderAnnotation.class)
@ExtendWith(VertxExtension.class)
public class ObjectStorageClientTest {

  protected static CephRadosGatwayContainer container = new CephRadosGatwayContainer()
      .withAccessKey("demo-key")
      .withSecretKey("demo-secret")
      .withBucketName("demo-bucket");

  protected static ObjectStorageClient client;

  @BeforeAll
  static void beforeAll(Vertx vertx, VertxTestContext testContext) {
    container.start();

    S3Options s3Options = new S3Options()
        .setSignatureVersion(S3SignatureVersion.V4)
        .setAccessKey(container.getAccessKey())
        .setSecretKey(container.getSecretKey())
        .setRegion("default");

    ObjectStorageClientOptions options = new ObjectStorageClientOptions()
        .setS3Options(s3Options)
        .setDefaultHost(container.getHost())
        .setDefaultPort(container.getMappedPort(7443))
        .setSsl(true)
        .setTrustAll(true);

    client = ObjectStorageClient.create(vertx, options);
    testContext.completeNow();
  }

  @Order(1)
  @Test
  void testPutObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + container.getBucketName() + "/cool.txt";
    Buffer buffer = vertx.fileSystem().readFileBlocking("fun.txt");
    ObjectOptions options = new ObjectOptions()
        .contentType("text/plain")
        .storageClass(StorageClass.STANDARD)
        .acl(Acl.PUBLIC_READ);
    client.put(options, path, buffer)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertNotNull(r.ETag());
          testContext.completeNow();
        })));
  }

  @Order(2)
  @Test
  void testCopyObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String source = "/" + container.getBucketName() + "/cool.txt";
    String target = "/" + container.getBucketName() + "/cool2.txt";
    ObjectOptions options = new ObjectOptions()
        .contentType("text/plain")
        .storageClass(StorageClass.STANDARD)
        .acl(Acl.PUBLIC_READ);
    client.copy(options, source, target)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertNotNull(r.bodyAsString());
          testContext.completeNow();
        })));
  }

  @Order(3)
  @Test
  void testGetObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + container.getBucketName() + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.get(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertEquals("text/plain", r.contentType());
          assertEquals("STANDARD", r.storageClass());
          assertEquals("Hi!", r.bodyAsString());
          testContext.completeNow();
        })));
  }

  @Order(4)
  @Test
  void testObjectInfoSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + container.getBucketName() + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.info(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertEquals("text/plain", r.contentType());
          assertEquals("STANDARD", r.storageClass());
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @Order(5)
  @Test
  void testObjectAclSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + container.getBucketName() + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.acl(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertEquals("application/xml", r.contentType());
          assertNotNull(r.requestId());
          assertNotNull(r.body());
          testContext.completeNow();
        })));
  }

  @Order(6)
  @Test
  void testDeleteObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + container.getBucketName() + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.delete(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(204, r.statusCode());
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @Order(7)
  @Test
  void testPutBucketSucceeded(VertxTestContext testContext) {
    String path = "/new-bucket";
    BucketOptions options = new BucketOptions();
    client.put(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @AfterAll
  static void afterAll() {
    client.close();
    container.stop();
  }

}
