package examples;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3CredentialsProvider;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
