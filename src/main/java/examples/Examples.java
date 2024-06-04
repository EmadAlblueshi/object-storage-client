package examples;

import io.github.emadalblueshi.objectstorage.client.Acl;
import io.github.emadalblueshi.objectstorage.client.BucketOptions;
import io.github.emadalblueshi.objectstorage.client.ObjectOptions;
import io.github.emadalblueshi.objectstorage.client.ObjectStorageClient;
import io.github.emadalblueshi.objectstorage.client.ObjectStorageClientOptions;
import io.github.emadalblueshi.objectstorage.client.S3Options;
import io.github.emadalblueshi.objectstorage.client.S3SignatureVersion;
import io.github.emadalblueshi.objectstorage.client.StorageClass;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

public class Examples {

  @SuppressWarnings("unused")
  void example1() {
    var s3Options = new S3Options()
        .setSignatureVersion(S3SignatureVersion.V4)
        .setAccessKey("access-key")
        .setSecretKey("secret-key")
        .setRegion("region");
  }

  @SuppressWarnings("unused")
  void example2(S3Options s3Options, Vertx vertx) {
    var clientOptions = new ObjectStorageClientOptions()
        .setS3Options(s3Options)
        .setDefaultHost("localhost")
        .setDefaultPort(443)
        .setSsl(true);
    // Create the client
    var client = ObjectStorageClient.create(vertx, clientOptions);
  }

  void example3(ObjectStorageClient client) {
    client.put(new BucketOptions(), "/my-bucket").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Bucket created");
      } else {
        System.out.println("Bucket failed");
      }
    });
  }

  @SuppressWarnings("unused")
  void example4() {
    ObjectOptions objectOptions = new ObjectOptions()
        .contentType("text/plain")
        .storageClass(StorageClass.STANDARD)
        .acl(Acl.PUBLIC_READ);
  }

  @SuppressWarnings("unused")
  void example5(Vertx vertx) {
    Buffer fileBuffer = vertx.fileSystem().readFileBlocking("readme.txt");
  }

  void example6(ObjectStorageClient client, ObjectOptions objectOptions, Buffer fileBuffer) {
    client.put(objectOptions, "/my-bucket/my-file.txt", fileBuffer).onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Object created");
      } else {
        System.out.println("Object failed");
      }
    });
  }

  void example7(ObjectStorageClient client) {
    client.get(new ObjectOptions(), "/my-bucket/my-file.txt").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println(r.result().bodyAsString());
      } else {
        System.out.println("Failed!");
      }
    });
  }

}
