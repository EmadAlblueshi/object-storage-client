package io.github.emadalblueshi.vertx.s3.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.vertx.core.Future;

/**
 * What an integration test creates on a backend, so it can all be deleted afterwards, also when
 * tests fail. Safe for real accounts: every bucket gets a unique {@link #PREFIX}ed name, and a
 * resource is registered <em>before</em> the request that creates it is sent.
 */
public final class TestResources {

  /** Prefix of every bucket the integration tests create. */
  public static final String PREFIX = "vertx-s3-it-";

  private final Set<String> buckets = ConcurrentHashMap.newKeySet();
  private final Map<String, Set<String>> keys = new ConcurrentHashMap<>();

  /** A new unique, valid bucket name with the {@link #PREFIX}. Not registered. */
  public static String uniqueBucketName() {
    return PREFIX + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Client options for {@code backend}: endpoint, region, credentials, trust for its TLS
   * certificate, and the addressing style an {@link ExternalBackend} asks for.
   */
  public static S3ClientOptions clientOptions(S3TestBackend backend) {
    S3ClientOptions options = new S3ClientOptions()
      .setEndpoint(backend.endpoint().toString())
      .setRegion(backend.region())
      .setCredentials(new S3Credentials(backend.accessKey(), backend.secretKey()));
    if (backend.tls()) {
      options.getHttpClientOptions().setTrustOptions(backend.httpClientOptions().getTrustOptions());
    }
    if (backend instanceof ExternalBackend) {
      options.setPathStyleAccess(((ExternalBackend) backend).pathStyleAccess());
    }
    return options;
  }

  /** Registers {@code bucket} for deletion and returns it. */
  public String bucket(String bucket) {
    buckets.add(bucket);
    return bucket;
  }

  /** Registers object {@code key} in {@code bucket} for deletion and returns the key. */
  public String key(String bucket, String key) {
    keys.computeIfAbsent(bucket, b -> ConcurrentHashMap.newKeySet()).add(key);
    return key;
  }

  /**
   * Deletes every registered object, then every registered bucket. Objects that are already gone
   * or were never created are fine. Fails, naming them, only if registered buckets remain.
   */
  public Future<Void> cleanUp(S3Client client) {
    List<Future<?>> objects = new ArrayList<>();
    keys.forEach((bucket, bucketKeys) -> bucketKeys.forEach(key -> objects.add(client.deleteObject(bucket, key).otherwiseEmpty())));
    return Future.join(objects).otherwiseEmpty().compose(v -> {
      List<String> left = new ArrayList<>();
      List<Future<?>> deletions = new ArrayList<>();
      for (String bucket : buckets) {
        deletions.add(client.deleteBucket(bucket).recover(e -> {
          if (!(e instanceof S3ServiceException && ((S3ServiceException) e).getStatusCode() == 404)) {
            synchronized (left) {
              left.add(bucket + " (" + e.getMessage() + ")");
            }
          }
          return Future.succeededFuture();
        }));
      }
      return Future.join(deletions).otherwiseEmpty().compose(w -> left.isEmpty()
        ? Future.<Void>succeededFuture()
        : Future.<Void>failedFuture("Test buckets left behind: " + left));
    });
  }
}
