package examples;

import java.util.List;
import java.util.Map;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3CredentialsProvider;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.PemTrustOptions;

/**
 * Code snippets referenced from {@code src/main/asciidoc/index.adoc} through docgen
 * ({@code {@link examples.Examples#method}}). Snippets are added together with the operations
 * they document.
 */
@SuppressWarnings("unused")
public class Examples {

  public void createForSpaces(Vertx vertx) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://nyc3.digitaloceanspaces.com")
      .setRegion("nyc3")
      .setCredentials(new S3Credentials("ACCESS_KEY", "SECRET_KEY"));

    S3Client client = S3Client.create(vertx, options);
  }

  public void createForLocalMinio(Vertx vertx) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("http://localhost:9000")
      .setRegion("us-east-1")
      .setCredentials(new S3Credentials("minioadmin", "minioadmin"));

    S3Client client = S3Client.create(vertx, options);
  }

  public void virtualHostedStyle(Vertx vertx) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://s3.eu-west-1.amazonaws.com")
      .setRegion("eu-west-1")
      // Path-style is the default here; the AWS SDK defaults to virtual-hosted-style
      .setPathStyleAccess(false)
      .setCredentials(new S3Credentials("ACCESS_KEY", "SECRET_KEY"));
  }

  public void transportOptions(Vertx vertx) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://minio.internal:9000")
      .setRegion("us-east-1")
      .setCredentials(new S3Credentials("ACCESS_KEY", "SECRET_KEY"));
    // TLS trust, timeouts and pool sizes; scheme, host and port always come from the endpoint
    options.getHttpClientOptions()
      .setTrustOptions(new PemTrustOptions().addCertPath("/etc/ssl/internal-ca.pem"))
      .setConnectTimeout(5_000);
    options.getPoolOptions().setHttp1MaxSize(32);
  }

  public void rotatingCredentials(Vertx vertx, RotatingSecret secret) {
    // Called for every request, possibly on an event loop: return a value held in memory, never block
    S3CredentialsProvider provider = () -> new S3Credentials(secret.accessKey(), secret.secretKey());

    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint("https://nyc3.digitaloceanspaces.com")
      .setRegion("nyc3")
      .setCredentialsProvider(provider);
  }

  public void createBucket(S3Client client) {
    client.createBucket("my-bucket")
      .onSuccess(v -> System.out.println("created"));
  }

  public void bucketExists(S3Client client) {
    // headBucket fails with HTTP 404 if the bucket doesn't exist (HEAD responses have no error code)
    Future<Boolean> exists = client.headBucket("my-bucket")
      .map(true)
      .recover(err -> err instanceof S3ServiceException && ((S3ServiceException) err).getStatusCode() == 404
        ? Future.succeededFuture(false)
        : Future.failedFuture(err));
  }

  public void deleteBucket(S3Client client) {
    // The bucket must be empty
    client.deleteBucket("my-bucket")
      .onSuccess(v -> System.out.println("deleted"));
  }

  public void putObject(S3Client client) {
    client.putObject("my-bucket", "reports/2026/summary.txt", Buffer.buffer("hello"))
      .onSuccess(etag -> System.out.println("stored, ETag " + etag));
  }

  public void putObjectWithOptions(S3Client client, Buffer png) {
    S3PutObjectOptions options = new S3PutObjectOptions()
      .setContentType("image/png")
      .setMetadata(Map.of("uploaded-by", "user-42"));
    client.putObject("my-bucket", "avatars/user-42.png", png, options);
  }

  public void getObject(S3Client client) {
    client.getObject("my-bucket", "reports/2026/summary.txt")
      .onSuccess(body -> System.out.println(body.toString()));
  }

  public void getObjectStreamToFile(Vertx vertx, S3Client client) {
    client.getObjectStream("my-bucket", "videos/big.mp4")
      .compose(stream -> vertx.fileSystem()
        .open("/tmp/big.mp4", new OpenOptions().setWrite(true).setCreate(true))
        // the stream must be consumed or cancelled: cancel it if the file can't be opened
        .recover(err -> stream.cancel().compose(v -> Future.failedFuture(err)))
        .compose(file -> stream.pipeTo(file)))
      .onSuccess(v -> System.out.println("downloaded"));
  }

  public void getObjectStreamToHttpResponse(S3Client client, HttpServerRequest request) {
    client.getObjectStream("my-bucket", "videos/big.mp4").onComplete(ar -> {
      HttpServerResponse response = request.response();
      if (ar.failed()) {
        response.setStatusCode(502).end();
        return;
      }
      S3ObjectStream stream = ar.result();
      if (stream.metadata().getContentType() != null) {
        response.putHeader("Content-Type", stream.metadata().getContentType());
      }
      stream.pipeTo(response);
    });
  }

  public void getObjectRange(S3Client client) {
    client.getObjectStream("my-bucket", "videos/big.mp4", "bytes=0-1048575")
      .onSuccess(stream -> System.out.println("first MiB: " + stream.metadata().getContentLength() + " bytes"));
  }

  public void cancelObjectStream(S3Client client) {
    client.getObjectStream("my-bucket", "videos/big.mp4").onSuccess(stream -> {
      if (stream.metadata().getContentLength() > 100 * 1024 * 1024) {
        stream.cancel();
      } else {
        stream.handler(chunk -> System.out.println(chunk.length() + " bytes")).resume();
      }
    });
  }

  public void listObjects(S3Client client) {
    client.listObjects("my-bucket", new S3ListObjectsOptions().setPrefix("reports/2026/"))
      .onSuccess(page -> page.getObjects().forEach(object ->
        System.out.println(object.getKey() + " (" + object.getSize() + " bytes)")));
  }

  public void listFolders(S3Client client) {
    client.listObjects("my-bucket", new S3ListObjectsOptions().setPrefix("reports/").setDelimiter("/"))
      .onSuccess(page -> {
        page.getCommonPrefixes().forEach(folder -> System.out.println("folder " + folder));
        page.getObjects().forEach(object -> System.out.println("file " + object.getKey()));
      });
  }

  public Future<List<String>> listAllKeys(S3Client client, S3ListObjectsOptions options, List<String> keys) {
    return client.listObjects("my-bucket", options).compose(page -> {
      page.getObjects().forEach(object -> keys.add(object.getKey()));
      if (!page.isTruncated()) {
        return Future.succeededFuture(keys);
      }
      // Same prefix and delimiter, next page
      return listAllKeys(client, new S3ListObjectsOptions(options).setContinuationToken(page.getNextContinuationToken()), keys);
    });
  }

  public void headObject(S3Client client) {
    client.headObject("my-bucket", "avatars/user-42.png").onSuccess(metadata -> System.out.println(
      metadata.getContentLength() + " bytes, " + metadata.getContentType() + ", " + metadata.getMetadata()));
  }

  public void deleteObject(S3Client client) {
    client.deleteObject("my-bucket", "avatars/user-42.png");
  }

  public void listBuckets(S3Client client) {
    client.listBuckets().onSuccess(result -> result.getBuckets()
      .forEach(bucket -> System.out.println(bucket.getName() + " created " + bucket.getCreationDate())));
  }

  public void handleErrors(S3Client client) {
    client.listBuckets().onFailure(err -> {
      if (err instanceof S3ServiceException) {
        S3ServiceException e = (S3ServiceException) err;
        System.out.println("S3 error " + e.getErrorCode() + " (HTTP " + e.getStatusCode()
          + "), request id " + e.getRequestId());
      } else {
        System.out.println("Request failed: " + err.getMessage());
      }
    });
  }

  public void close(S3Client client) {
    client.close().onSuccess(v -> System.out.println("closed"));
  }

  /** Placeholder for an application-managed secret (e.g. a watched Kubernetes secret). */
  public interface RotatingSecret {
    String accessKey();

    String secretKey();
  }
}
