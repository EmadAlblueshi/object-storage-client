package examples;

import io.github.emadalblueshi.objectstorage.client.s3.*;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.web.client.WebClientOptions;

@SuppressWarnings("unused")
public class Examples {

  void example1() {
    var authOptions = new S3AuthOptions()
        .setSignatureVersion(S3SignatureVersion.V4)
        .setAccessKey("access-key")
        .setSecretKey("secret-key");
  }

  void example2(S3AuthOptions authOptions, Vertx vertx) {
    var clientOptions = new S3ClientOptions()
        .setAuthOptions(authOptions)
        .setRegion("region")
        .setWebClientOptions(new WebClientOptions()
            .setDefaultHost("s3.example.com")
            .setDefaultPort(443)
            .setSsl(true));

    // Create the client
    var client = S3Client.create(vertx, clientOptions);
  }

  void example3(S3Client client) {
    client.putBucket(new S3BucketOptions(), "/my-bucket").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Put bucket succeeded");
      } else {
        System.out.println("Put bucket failed!");
      }
    });
  }

  void example4() {
    // Create object request options with content type
    S3ObjectOptions s3ObjectOptions = new S3ObjectOptions()
        .contentType("text/plain");
  }

  void example5(Vertx vertx) {
    Buffer fileBuffer = vertx.fileSystem().readFileBlocking("readme.txt");
  }

  void example6(S3Client client, S3ObjectOptions s3ObjectOptions, Buffer fileBuffer) {
    client.putObject(s3ObjectOptions, "/my-bucket/my-file.txt", fileBuffer).onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Put object succeeded");
      } else {
        System.out.println("Put object failed!");
      }
    });
  }

  void example7(S3Client client) {
    client.getObject(new S3ObjectOptions(), "/my-bucket/my-object.txt").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println(r.result().bodyAsString());
      } else {
        System.out.println("Get object failed!");
      }
    });
  }

  void example8(Vertx vertx, S3Client client) {
    AsyncFile asyncFile = vertx.fileSystem().openBlocking("large-image.png", new OpenOptions().setRead(true));
    // Object content type
    S3ObjectOptions options = new S3ObjectOptions().contentType("image/png");
    // Upload large object as ReadStream<Buffer>
    client.putObjectAsStream(options, "/my-bucket/large-object.png", asyncFile).onComplete(res -> {
      if(res.succeeded()) {
        System.out.println("Put large object as stream succeeded");
      } else {
        System.out.println("Put large object as stream failed!");
      }
    });
  }

  void all(Vertx vertx) {

    // Create auth options
    var authOptions = new S3AuthOptions()
        .setSignatureVersion(S3SignatureVersion.V4)
        .setAccessKey("access-key")
        .setSecretKey("secret-key");

    // Create client options
    var clientOptions = new S3ClientOptions()
        .setAuthOptions(authOptions)
        .setRegion("region")
        .setWebClientOptions(new WebClientOptions()
            .setDefaultHost("s3.example.com")
            .setDefaultPort(443)
            .setSsl(true));

    // Create the client
    var client = S3Client.create(vertx, clientOptions);

    // Create new bucket
    client.putBucket(new S3BucketOptions(), "/my-bucket").onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Put bucket succeeded");
      } else {
        System.out.println("Put bucket failed!");
      }
    });

    // Create object request options with content type
    var objectOptions = new S3ObjectOptions()
        .contentType("text/plain");

    // Read existing text file
    Buffer fileBuffer = vertx.fileSystem().readFileBlocking("readme.txt");

    // Create new object
    client.putObject(objectOptions, "/my-bucket/my-file.txt", fileBuffer).onComplete(r -> {
      if (r.succeeded()) {
        System.out.println("Put object succeeded");
      } else {
        System.out.println("Put object failed!");
      }
    });

    // The S3 Client now supports streaming for large objects
    AsyncFile asyncFile = vertx.fileSystem().openBlocking("large-image.png", new OpenOptions().setRead(true));
    // Object content type
    S3ObjectOptions options = new S3ObjectOptions().contentType("image/png");
    // Upload large object as ReadStream<Buffer>
    client.putObjectAsStream(options, "/my-bucket/large-object.png", asyncFile).onComplete(res -> {
      if(res.succeeded()) {
        System.out.println("Put large object as stream succeeded");
      } else {
        System.out.println("Put large object as stream failed!");
      }
    });

  }

}
