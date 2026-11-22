package examples;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3PresignGetOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignPutOptions;
import io.github.emadalblueshi.vertx.s3.S3PresignedRequest;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.S3UploadedPart;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
      // Spaces documents virtual-hosted-style addressing: https://bucket.nyc3.digitaloceanspaces.com/key
      .setPathStyleAccess(false)
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

  public void createFromJsonConfig(Vertx vertx, JsonObject config) {
    JsonObject s3 = config.getJsonObject("s3");
    // e.g. {"endpoint": "https://nyc3.digitaloceanspaces.com", "region": "nyc3",
    //       "credentials": {"accessKey": "...", "secretKey": "..."}}
    S3ClientOptions options = new S3ClientOptions(s3);

    S3Client client = S3Client.create(vertx, options);
  }

  public void createBucket(S3Client client) {
    client.createBucket("my-bucket")
      .onSuccess(v -> System.out.println("created"));
  }

  public void bucketExists(S3Client client) {
    Future<Boolean> exists = client.headBucket("my-bucket")
      .map(true)
      // A missing bucket fails with HTTP 404 (HEAD responses have no error code)
      .recover(err -> err instanceof S3ServiceException && ((S3ServiceException) err).getStatusCode() == 404
        ? Future.succeededFuture(false)
        : Future.failedFuture(err));
  }

  public void deleteBucket(S3Client client) {
    client.deleteBucket("my-bucket")
      // Fails with BucketNotEmpty (HTTP 409) unless the bucket is empty
      .onSuccess(v -> System.out.println("deleted"));
  }

  public void putObject(S3Client client) {
    client.putObject("my-bucket", "reports/2026/summary.txt", Buffer.buffer("hello"))
      .onSuccess(etag -> System.out.println("stored, ETag " + etag));
  }

  public void putObjectWithOptions(S3Client client, Buffer png) {
    S3PutObjectOptions options = new S3PutObjectOptions()
      .setContentType("image/png")
      .putUserMetadata("uploaded-by", "user-42");
    client.putObject("my-bucket", "avatars/user-42.png", png, options);
  }

  public void putFile(S3Client client) {
    client.putFile("my-bucket", "videos/big.mp4", "/data/big.mp4", new S3PutObjectOptions().setContentType("video/mp4"))
      .onSuccess(etag -> System.out.println("uploaded, ETag " + etag));
  }

  public void putObjectFromRequest(S3Client client, HttpServerRequest request) {
    String key = "uploads/" + UUID.randomUUID();
    // The client pauses the request body at once, so no chunk is lost while the upload starts
    client.putObject("my-bucket", key, request, new S3PutObjectOptions().setContentType("application/octet-stream"))
      .onSuccess(etag -> request.response().setStatusCode(201).end(key))
      .onFailure(err -> request.response().setStatusCode(502).end());
  }

  public void getObject(S3Client client) {
    client.getObject("my-bucket", "reports/2026/summary.txt")
      .onSuccess(body -> System.out.println(body.toString()));
  }

  public void getObjectStreamToFile(Vertx vertx, S3Client client) {
    vertx.fileSystem().open("/tmp/big.mp4", new OpenOptions().setWrite(true).setTruncateExisting(true))
      // The file is open before the stream exists: once you have the stream, it must be consumed or cancelled
      .compose(file -> client.getObjectStream("my-bucket", "videos/big.mp4").compose(
        // pipeTo closes the file; if writing fails, cancel the stream instead of draining it
        stream -> stream.pipeTo(file).onFailure(err -> stream.cancel()),
        // no stream (e.g. NoSuchKey): just close the file
        err -> file.close().compose(v -> Future.failedFuture(err))))
      .onSuccess(v -> System.out.println("downloaded"));
  }

  public void getObjectStreamToHttpResponse(S3Client client, HttpServerRequest request) {
    HttpServerResponse response = request.response();
    client.getObjectStream("my-bucket", "videos/big.mp4").onComplete(ar -> {
      if (ar.failed()) {
        boolean missing = ar.cause() instanceof S3ServiceException
          && ((S3ServiceException) ar.cause()).getStatusCode() == 404;
        response.setStatusCode(missing ? 404 : 502).end();
        return;
      }
      S3ObjectStream stream = ar.result();
      S3ObjectMetadata metadata = stream.metadata();
      if (metadata.getContentType() != null) {
        response.putHeader("Content-Type", metadata.getContentType());
      }
      if (metadata.getContentLength() >= 0) {
        response.putHeader("Content-Length", Long.toString(metadata.getContentLength()));
      }
      // If the HTTP client goes away, cancel the stream: otherwise it keeps its S3 connection,
      // and Vert.x may download the rest of the object for nothing (cancel is a no-op once done)
      response.closeHandler(v -> stream.cancel());
      response.send(stream).onFailure(err -> stream.cancel());
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
      metadata.getContentLength() + " bytes, " + metadata.getContentType() + ", " + metadata.getUserMetadata()));
  }

  public void deleteObject(S3Client client) {
    client.deleteObject("my-bucket", "avatars/user-42.png");
  }

  public void createPresigner(Vertx vertx, S3ClientOptions options) {
    S3Client client = S3Client.create(vertx, options);
    // From a client: the client's options, always the same instance
    S3Presigner presigner = client.presigner();

    // Without Vert.x, e.g. in a service that only presigns
    S3Presigner standalone = S3Presigner.create(options);
  }

  public void presignerWithPublicEndpoint(Vertx vertx) {
    S3ClientOptions options = new S3ClientOptions()
      // The backend reaches the storage inside the cluster
      .setEndpoint("http://minio.storage.svc.cluster.local:9000")
      // Apps outside the cluster use the public host
      .setPublicEndpoint("https://files.example.com")
      .setRegion("us-east-1")
      .setCredentials(new S3Credentials("ACCESS_KEY", "SECRET_KEY"));
    S3Client client = S3Client.create(vertx, options);

    // https://files.example.com/my-bucket/avatars/user-42.png?X-Amz-Algorithm=...
    String url = client.presigner().presignPut("my-bucket", "avatars/user-42.png", 10, TimeUnit.MINUTES);
  }

  public JsonObject presignUpload(S3Presigner presigner, String userId, String contentType, String contentMd5) {
    String key = "uploads/" + userId + "/" + UUID.randomUUID();
    // contentType and contentMd5 come from the app, after your checks (e.g. an allowed type)
    S3PresignedRequest upload = presigner.presignPut("my-bucket", key, 10, TimeUnit.MINUTES,
      new S3PresignPutOptions().setContentType(contentType).setContentMd5(contentMd5));
    // Remember the key as this user's pending upload, and return
    // {"url": ..., "method": "PUT", "headers": {"Content-Type": ..., "Content-MD5": ...}}
    return upload.toJson().put("key", key);
  }

  public Future<Void> verifyUpload(S3Client client, String key) {
    return client.headObject("my-bucket", key).compose(metadata -> {
      if (metadata.getContentLength() <= 10 * 1024 * 1024 && "image/jpeg".equals(metadata.getContentType())) {
        return Future.succeededFuture();
      }
      // Too large, or another type: don't keep it
      return client.deleteObject("my-bucket", key).compose(v -> Future.failedFuture("upload rejected"));
    });
  }

  public void presignDownload(S3Presigner presigner) {
    String url = presigner.presignGet("my-bucket", "uploads/user-42/photo.jpg", 5, TimeUnit.MINUTES);

    // In Java, a Duration works too
    String same = presigner.presignGet("my-bucket", "uploads/user-42/photo.jpg", Duration.ofMinutes(5));
  }

  public void presignPrivateDownload(S3Presigner presigner) {
    S3PresignGetOptions options = new S3PresignGetOptions()
      .setContentType("application/pdf")
      // Saved as "Résumé 2026.pdf", whatever the key; never cached
      .setContentDisposition(S3PresignGetOptions.attachment("Résumé 2026.pdf"))
      .setCacheControl("no-store");
    String url = presigner.presignGet("my-bucket", "documents/42.pdf", 5, TimeUnit.MINUTES, options).getUrl();
  }

  public Future<JsonObject> startMultipartUpload(S3Client client, String userId, long size, String contentType) {
    String key = "videos/" + userId + "/" + UUID.randomUUID();
    long mib = 1024 * 1024;
    // Parts of at least 8 MiB, rounded up to whole MiB, and at most 10,000 of them
    long partSize = (Math.max(8 * mib, (size + 9_999) / 10_000) + mib - 1) / mib * mib;
    long parts = (size + partSize - 1) / partSize;
    return client.createMultipartUpload("my-bucket", key, new S3PutObjectOptions().setContentType(contentType))
      // Store the upload id with the user and the key before answering: it is needed to resume or clean up
      .map(uploadId -> new JsonObject().put("key", key).put("uploadId", uploadId).put("partSize", partSize)
        .put("parts", parts));
  }

  public JsonArray presignParts(S3Presigner presigner, String key, String uploadId, int first, int last) {
    JsonArray urls = new JsonArray();
    for (int part = first; part <= last; part++) {
      urls.add(new JsonObject().put("partNumber", part)
        .put("url", presigner.presignUploadPart("my-bucket", key, uploadId, part, 15, TimeUnit.MINUTES)));
    }
    // The app PUTs each part to its URL, a few at a time, and keeps the ETag header of each response
    return urls;
  }

  public Future<Void> completeMultipartUpload(S3Client client, String key, String uploadId, JsonArray reported,
                                              long maxSize) {
    List<S3CompletedPart> parts = reported.stream()
      .map(part -> new S3CompletedPart((JsonObject) part))
      .collect(Collectors.toList());
    // Reported as [{"partNumber": 1, "etag": "\"...\""}, ...], in any order
    return client.completeMultipartUpload("my-bucket", key, uploadId, parts)
      .compose(etag -> client.headObject("my-bucket", key))
      .compose(metadata -> metadata.getContentLength() <= maxSize
        ? Future.<Void>succeededFuture()
        : client.deleteObject("my-bucket", key).compose(v -> Future.<Void>failedFuture("upload too large")));
  }

  public Future<List<Integer>> missingParts(S3Client client, String key, String uploadId, int parts) {
    return client.listParts("my-bucket", key, uploadId).map(uploaded -> {
      Set<Integer> done = uploaded.stream().map(S3UploadedPart::getPartNumber).collect(Collectors.toSet());
      // Presign only these again; listParts also returns the ETags of the parts already uploaded
      return IntStream.rangeClosed(1, parts).filter(n -> !done.contains(n)).boxed().collect(Collectors.toList());
    });
  }

  public Future<Void> abortStaleUploads(S3Client client, Instant startedBefore) {
    return client.listMultipartUploads("my-bucket", null).compose(uploads -> {
      List<Future<Void>> aborts = uploads.stream()
        .filter(upload -> upload.getInitiated() != null && upload.getInitiated().isBefore(startedBefore))
        .map(upload -> client.abortMultipartUpload("my-bucket", upload.getKey(), upload.getUploadId())
          // Already gone (404 NoSuchUpload) counts as done
          .recover(err -> err instanceof S3ServiceException && ((S3ServiceException) err).getStatusCode() == 404
            ? Future.succeededFuture()
            : Future.failedFuture(err)))
        .collect(Collectors.toList());
      return Future.all(aborts).mapEmpty();
    });
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
}
