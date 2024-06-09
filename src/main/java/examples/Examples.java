package examples;

import io.github.emadalblueshi.objectstorage.client.s3.*;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

@SuppressWarnings("unused")
public class Examples {

  void example1() {
    var authOptions = new S3AuthOptions()
        .setSignatureVersion(SignatureVersion.V4)
        .setAccessKey("access-key")
        .setSecretKey("secret-key");
  }

  void example2(S3AuthOptions authOptions, Vertx vertx) {
    var clientOptions = new S3ClientOptions()
        .setAuthOptions(authOptions)
        .setRegion("region")
        .setHost("s3.example.com")
        .setPort(443)
        .setSsl(true);

    // Create the client
    var client = S3Client.create(vertx, clientOptions);
  }

  void example3(S3Client client) {
    client.put(new BucketOptions(), "/my-bucket").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Bucket created");
      } else {
        System.out.println("Bucket failed");
      }
    });
  }

  void example4() {
    ObjectOptions objectOptions = new ObjectOptions()
        .contentType("text/plain")
        .storageClass(StorageClass.STANDARD)
        .acl(Acl.PUBLIC_READ);
  }

  void example5(Vertx vertx) {
    Buffer fileBuffer = vertx.fileSystem().readFileBlocking("readme.txt");
  }

  void example6(S3Client client, ObjectOptions objectOptions, Buffer fileBuffer) {
    client.put(objectOptions, "/my-bucket/my-file.txt", fileBuffer).onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Object created");
      } else {
        System.out.println("Object failed");
      }
    });
  }

  void example7(S3Client client) {
    client.get(new ObjectOptions(), "/my-bucket/my-file.txt").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println(r.result().bodyAsString());
      } else {
        System.out.println("Failed!");
      }
    });
  }

  void all(Vertx vertx) {

    // Create auth options
    var authOptions = new S3AuthOptions()
        .setSignatureVersion(SignatureVersion.V4)
        .setAccessKey("access-key")
        .setSecretKey("secret-key");

    // Create client options
    var clientOptions = new S3ClientOptions()
        .setAuthOptions(authOptions)
        .setRegion("region")
        .setHost("s3.example.com")
        .setPort(443)
        .setSsl(true);

    // Create the client
    var client = S3Client.create(vertx, clientOptions);

    // Create new bucket
    client.put(new BucketOptions(), "/my-bucket").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Bucket created");
      } else {
        System.out.println("Bucket failed");
      }
    });

    // Create object request options
    var objectOptions = new ObjectOptions()
        .contentType("text/plain")
        .storageClass(StorageClass.STANDARD)
        .acl(Acl.PUBLIC_READ);

    // Read existing text file
    Buffer fileBuffer = vertx.fileSystem().readFileBlocking("readme.txt");

    // Create new object
    client.put(objectOptions, "/my-bucket/my-file.txt", fileBuffer).onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Object created");
      } else {
        System.out.println("Object failed");
      }
    });

  }

}
