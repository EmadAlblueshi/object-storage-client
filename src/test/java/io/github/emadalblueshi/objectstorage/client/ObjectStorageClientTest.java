package io.github.emadalblueshi.objectstorage.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.CachingWebClientOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

@ExtendWith(VertxExtension.class)
@TestMethodOrder(OrderAnnotation.class)
public class ObjectStorageClientTest {

  protected static MinIOS3Container container = new MinIOS3Container();
  protected static ObjectStorageClient client;
  protected static String HOST;
  protected static Integer PORT_S3_API;
  protected static Integer PORT_WEB_UI;
  protected static String ACCESS_KEY;
  protected static String SECRET_KEY;
  protected static String BUCKET_NAME = "demo-bucket";

  @BeforeAll
  static void beforeAll(Vertx vertx, VertxTestContext testContext) {
    container.start();

    HOST = container.getHost();
    PORT_S3_API = container.getMappedPort(9000);
    PORT_WEB_UI = container.getMappedPort(9001);
    ACCESS_KEY = container.getAccessKey();
    SECRET_KEY = container.getSecretKey();

    var s3Options = new S3Options()
        .setSignatureVersion(S3SignatureVersion.V4)
        .setAccessKey(ACCESS_KEY)
        .setSecretKey(SECRET_KEY)
        .setRegion("default");

    var options = new ObjectStorageClientOptions()
        .setS3Options(s3Options)
        .setDefaultHost(HOST)
        .setDefaultPort(PORT_S3_API);

    client = ObjectStorageClient.create(vertx, options);

    var wOptions = new CachingWebClientOptions()
        .setEnableVaryCaching(true)
        .setLogActivity(true)
        .setUserAgentEnabled(true)
        .setKeepAlive(true)
        .setDefaultHost(HOST)
        .setDefaultPort(PORT_WEB_UI)
        .setFollowRedirects(true);

    var login = new JsonObject()
        .put("accessKey", ACCESS_KEY)
        .put("secretKey", SECRET_KEY);

    var bucketJson = vertx.fileSystem().readFileBlocking("bucket.json").toJsonObject();
    bucketJson.put("name", BUCKET_NAME);

    var wClient = WebClient.create(vertx, wOptions);
    var sClient = WebClientSession.create(wClient);

    sClient
        .post("/api/v1/login")
        .putHeader("content-type", "application/json")
        .sendJsonObject(login)
        .compose(n -> sClient.post("/api/v1/buckets")
            .putHeader("content-type", "application/json")
            .sendJsonObject(bucketJson))
        .onComplete(testContext.succeeding(r -> {
          testContext.completeNow();
        }));
  }

  @Order(1)
  @Test
  void testPutNewBucketSucceeded(VertxTestContext testContext) {
    String path = "/new-bucket";
    BucketOptions options = new BucketOptions();
    client.put(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @Order(2)
  @Test
  void testPutObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
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

  @Order(3)
  @Test
  void testCopyObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String source = "/" + BUCKET_NAME + "/cool.txt";
    String target = "/" + BUCKET_NAME + "/cool2.txt";
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

  @Order(4)
  @Test
  void testGetObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.get(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertEquals("text/plain", r.contentType());
          // Ceph radosgw returns the storage only
          // assertEquals("STANDARD", r.storageClass());
          assertEquals("Hi!", r.bodyAsString());
          testContext.completeNow();
        })));
  }

  @Order(5)
  @Test
  void testObjectInfoSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.info(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(200, r.statusCode());
          assertEquals("text/plain", r.contentType());
          // Ceph radosgw returns the storage only
          // assertEquals("STANDARD", r.storageClass());
          assertNotNull(r.requestId());
          testContext.completeNow();
        })));
  }

  @Order(6)
  @Test
  void testObjectAclSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.acl(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          // Ceph radosgw returns the correct content type only
          // assertEquals("application/xml", r.contentType());
          assertEquals(200, r.statusCode());
          assertNotNull(r.requestId());
          assertNotNull(r.body());
          testContext.completeNow();
        })));
  }

  @Order(7)
  @Test
  void testDeleteObjectSucceeded(Vertx vertx, VertxTestContext testContext) {
    String path = "/" + BUCKET_NAME + "/cool.txt";
    ObjectOptions options = new ObjectOptions();
    client.delete(options, path)
        .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
          assertEquals(204, r.statusCode());
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
