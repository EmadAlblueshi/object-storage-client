package io.github.emadalblueshi.vertx.s3.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientException;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3CompletedPart;
import io.github.emadalblueshi.vertx.s3.S3ListBucketsResult;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsResult;
import io.github.emadalblueshi.vertx.s3.S3MultipartUpload;
import io.github.emadalblueshi.vertx.s3.S3ObjectMetadata;
import io.github.emadalblueshi.vertx.s3.S3ObjectStream;
import io.github.emadalblueshi.vertx.s3.S3Presigner;
import io.github.emadalblueshi.vertx.s3.S3PutObjectOptions;
import io.github.emadalblueshi.vertx.s3.S3ServiceException;
import io.github.emadalblueshi.vertx.s3.S3UploadedPart;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;

/**
 * The {@link S3Client} implementation.
 * <p>
 * Public only because {@link S3Client#create} instantiates it across packages (allowlisted in
 * {@code PackagePolicyTest}). It is internal: the module doesn't export this package.
 * <p>
 * Every operation captures the caller's context first and returns a Future bound to it (Vert.x
 * rule 3). After construction nothing throws to the caller: a closed client and invalid
 * arguments are reported as failed Futures, before any I/O.
 */
public final class S3ClientImpl implements S3Client {

  /** AWS rejects a {@code LocationConstraint} for this region: it is the default. */
  private static final String US_EAST_1 = "us-east-1";
  /** The part size of streamed uploads, whose length isn't known: 10,000 parts make 78 GiB. */
  static final int STREAM_PART_SIZE = 8 * 1024 * 1024;

  private final Vertx vertx;
  private final ClientConfig config;
  private final Transport transport;
  private final S3Presigner presigner;
  /** Registration with the creating context (or the Vertx instance), which closes the client when it closes. */
  private final ContextSupport.Registration registration;
  /** The shutdown Future, set by the first close (explicit or by the owner); non-null means closed. */
  private volatile Future<Void> shutdown;
  /** Streamed uploads in progress, aborted when the client closes. Guarded by {@code this} for additions. */
  private final Set<StreamingUpload> uploads = ConcurrentHashMap.newKeySet();

  public S3ClientImpl(Vertx vertx, S3ClientOptions options) {
    this.vertx = Objects.requireNonNull(vertx, "vertx");
    this.config = ClientConfig.from(options);
    this.transport = new Transport(vertx, config);
    this.presigner = new S3PresignerImpl(config, transport.signer());
    ContextSupport.Registration registered;
    try {
      registered = ContextSupport.registerResource(vertx, this::shutdown);
    } catch (IllegalStateException e) {
      transport.close();
      throw e;
    }
    this.registration = registered;
  }

  @Override
  public S3Presigner presigner() {
    return presigner;
  }

  @Override
  public Future<S3ListBucketsResult> listBuckets() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, null, () -> transport.execute(caller, S3Request.service(HttpMethod.GET)))
      .compose(response -> {
        try {
          return caller.succeeded(ResponseParsers.listBuckets(response.body));
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListBuckets response: " + e.getMessage(), e));
        }
      });
  }

  @Override
  public Future<Void> createBucket(String bucket) {
    return bucketRequest(HttpMethod.PUT, bucket, createBucketBody(config.region));
  }

  @Override
  public Future<Void> headBucket(String bucket) {
    return bucketRequest(HttpMethod.HEAD, bucket, null);
  }

  @Override
  public Future<Void> deleteBucket(String bucket) {
    return bucketRequest(HttpMethod.DELETE, bucket, null);
  }

  @Override
  public Future<S3ListObjectsResult> listObjects(String bucket, S3ListObjectsOptions options) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = BucketNames.problem(bucket, config);
    if (problem == null) {
      problem = ObjectRequests.listProblem(options);
    }
    return call(caller, problem, () -> transport.execute(caller,
        new S3Request(HttpMethod.GET, bucket, null, ObjectRequests.listQuery(options), List.of(), null)))
      .compose(response -> {
        S3ListObjectsResult result;
        try {
          result = ResponseParsers.listObjects(response.body);
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListObjectsV2 response: " + e.getMessage(), e));
        }
        if (result.isTruncated() && result.getNextContinuationToken() == null) {
          // Returned as is, a "while (truncated)" paging loop would restart from page 1 forever
          return caller.failed(new S3ClientException("ListObjectsV2 response is truncated but has no continuation token", null));
        }
        return caller.succeeded(result);
      });
  }

  @Override
  public Future<String> putObject(String bucket, String key, Buffer body) {
    return putObject(bucket, key, body, null);
  }

  @Override
  public Future<String> putObject(String bucket, String key, Buffer body, S3PutObjectOptions options) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = objectProblem(bucket, key);
    List<Map.Entry<String, String>> headers = null;
    if (problem == null && body == null) {
      problem = "The body is required (use an empty Buffer for an empty object)";
    }
    if (problem == null) {
      try {
        headers = ObjectRequests.putHeaders(options);
      } catch (IllegalArgumentException e) {
        problem = e.getMessage();
      }
    }
    List<Map.Entry<String, String>> putHeaders = headers;
    return call(caller, problem, () -> putBuffered(caller, bucket, key, putHeaders, body, null));
  }

  @Override
  public Future<String> putObject(String bucket, String key, ReadStream<Buffer> body) {
    return putObject(bucket, key, body, null);
  }

  @Override
  public Future<String> putObject(String bucket, String key, ReadStream<Buffer> body, S3PutObjectOptions options) {
    return putObject(bucket, key, body, options, STREAM_PART_SIZE, MultipartRequests.MAX_PARTS);
  }

  /** {@link #putObject(String, String, ReadStream, S3PutObjectOptions)} with another part size and limit. For tests. */
  Future<String> putObject(String bucket, String key, ReadStream<Buffer> body, S3PutObjectOptions options, int partSize,
                           int maxParts) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    if (body != null) {
      // Before any async step, so no data is lost; it stays paused if the upload doesn't start
      body.pause();
    }
    String problem = objectProblem(bucket, key);
    List<Map.Entry<String, String>> headers = null;
    if (problem == null && body == null) {
      problem = "The body is required";
    }
    if (problem == null) {
      try {
        headers = ObjectRequests.putHeaders(options);
      } catch (IllegalArgumentException e) {
        problem = e.getMessage();
      }
    }
    List<Map.Entry<String, String>> putHeaders = headers;
    return call(caller, problem, () -> {
      StreamingUpload upload = new StreamingUpload(this, caller, bucket, key, putHeaders, body, partSize, maxParts,
        !config.tls);
      synchronized (this) {
        if (shutdown != null) {
          return caller.failed(new S3ClientException("The S3 client is closed", null));
        }
        uploads.add(upload);
      }
      return upload.start();
    });
  }

  @Override
  public Future<Buffer> getObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    int limit = config.maxBufferedObjectSize;
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.GET, bucket, key, List.of(), null), limit,
        size -> new S3ClientException("The object is larger than the getObject limit of " + limit + " bytes"
          + (size >= 0 ? " (" + size + " bytes)" : "") + ": use getObjectStream, or raise "
          + "S3ClientOptions.maxBufferedObjectSize", null)))
      .map(response -> response.body);
  }

  @Override
  public Future<S3ObjectStream> getObjectStream(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return objectStream(caller, bucket, key, objectProblem(bucket, key), List.of());
  }

  @Override
  public Future<S3ObjectStream> getObjectStream(String bucket, String key, String range) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = objectProblem(bucket, key);
    if (problem == null) {
      problem = ObjectRequests.rangeProblem(range);
    }
    return objectStream(caller, bucket, key, problem, List.of(Map.entry("Range", String.valueOf(range))));
  }

  @Override
  public Future<S3ObjectMetadata> headObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.HEAD, bucket, key, List.of(), null)))
      .map(response -> ObjectRequests.metadata(response.headers));
  }

  @Override
  public Future<Void> deleteObject(String bucket, String key) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, objectProblem(bucket, key), () -> transport.execute(caller,
        S3Request.object(HttpMethod.DELETE, bucket, key, List.of(), null)))
      .mapEmpty();
  }

  @Override
  public Future<String> createMultipartUpload(String bucket, String key, S3PutObjectOptions options) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = objectProblem(bucket, key);
    List<Map.Entry<String, String>> headers = null;
    if (problem == null) {
      try {
        headers = ObjectRequests.putHeaders(options);
      } catch (IllegalArgumentException e) {
        problem = e.getMessage();
      }
    }
    List<Map.Entry<String, String>> createHeaders = headers;
    return call(caller, problem, () -> createUpload(caller, bucket, key, createHeaders));
  }

  @Override
  public Future<String> completeMultipartUpload(String bucket, String key, String uploadId, List<S3CompletedPart> parts) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = uploadProblem(bucket, key, uploadId);
    Buffer body = null;
    if (problem == null) {
      try {
        body = MultipartRequests.completeBody(parts);
      } catch (IllegalArgumentException e) {
        problem = e.getMessage();
      }
    }
    Buffer completeBody = body;
    return call(caller, problem, () -> sendComplete(caller, bucket, key, uploadId, completeBody));
  }

  @Override
  public Future<Void> abortMultipartUpload(String bucket, String key, String uploadId) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, uploadProblem(bucket, key, uploadId), () -> abortUpload(caller, bucket, key, uploadId));
  }

  @Override
  public Future<List<S3UploadedPart>> listParts(String bucket, String key, String uploadId) {
    return listParts(bucket, key, uploadId, MultipartRequests.PAGE_SIZE);
  }

  /** {@link #listParts(String, String, String)} with pages of {@code pageSize} parts. For tests. */
  Future<List<S3UploadedPart>> listParts(String bucket, String key, String uploadId, int pageSize) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    int maxPages = (MultipartRequests.MAX_PARTS + pageSize - 1) / pageSize;
    return call(caller, uploadProblem(bucket, key, uploadId),
      () -> partsPage(caller, bucket, key, uploadId, pageSize, 0, 1, maxPages, new ArrayList<>()));
  }

  private Future<List<S3UploadedPart>> partsPage(ContextSupport.Caller caller, String bucket, String key, String uploadId,
                                                 int pageSize, int marker, int page, int maxPages,
                                                 List<S3UploadedPart> parts) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    query.add(Map.entry("max-parts", Integer.toString(pageSize)));
    if (marker > 0) {
      query.add(Map.entry("part-number-marker", Integer.toString(marker)));
    }
    query.add(Map.entry("uploadId", uploadId));
    return transport.execute(caller, new S3Request(HttpMethod.GET, bucket, key, query, List.of(), null))
      .compose(response -> {
        ResponseParsers.PartsPage result;
        try {
          result = ResponseParsers.listPartsPage(response.body);
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListParts response: " + e.getMessage(), e));
        }
        parts.addAll(result.parts);
        if (!result.truncated) {
          return caller.succeeded(parts);
        }
        // Without these guards, a server that doesn't advance would make the client loop forever
        if (result.nextPartNumberMarker == null || result.nextPartNumberMarker <= marker) {
          return caller.failed(new S3ClientException("ListParts response is truncated but its next part number "
            + "marker doesn't advance", null));
        }
        if (page >= maxPages) {
          return caller.failed(new S3ClientException("ListParts returned more than " + maxPages + " pages", null));
        }
        return partsPage(caller, bucket, key, uploadId, pageSize, result.nextPartNumberMarker, page + 1, maxPages, parts);
      });
  }

  @Override
  public Future<List<S3MultipartUpload>> listMultipartUploads(String bucket, String prefix) {
    return listMultipartUploads(bucket, prefix, MultipartRequests.PAGE_SIZE, MultipartRequests.MAX_UPLOAD_PAGES);
  }

  /** {@link #listMultipartUploads(String, String)} with pages of {@code pageSize}, at most {@code maxPages}. For tests. */
  Future<List<S3MultipartUpload>> listMultipartUploads(String bucket, String prefix, int pageSize, int maxPages) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    String problem = BucketNames.problem(bucket, config);
    if (problem == null) {
      problem = MultipartRequests.prefixProblem(prefix);
    }
    return call(caller, problem,
      () -> uploadsPage(caller, bucket, prefix, pageSize, null, null, 1, maxPages, new ArrayList<>()));
  }

  private Future<List<S3MultipartUpload>> uploadsPage(ContextSupport.Caller caller, String bucket, String prefix,
                                                      int pageSize, String keyMarker, String uploadIdMarker, int page,
                                                      int maxPages, List<S3MultipartUpload> uploads) {
    List<Map.Entry<String, String>> query = new ArrayList<>();
    query.add(Map.entry("encoding-type", "url"));
    if (keyMarker != null) {
      query.add(Map.entry("key-marker", keyMarker));
    }
    query.add(Map.entry("max-uploads", Integer.toString(pageSize)));
    if (prefix != null && !prefix.isEmpty()) {
      query.add(Map.entry("prefix", prefix));
    }
    if (uploadIdMarker != null) {
      query.add(Map.entry("upload-id-marker", uploadIdMarker));
    }
    query.add(Map.entry("uploads", ""));
    return transport.execute(caller, new S3Request(HttpMethod.GET, bucket, null, query, List.of(), null))
      .compose(response -> {
        ResponseParsers.UploadsPage result;
        try {
          result = ResponseParsers.listMultipartUploadsPage(response.body);
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid ListMultipartUploads response: " + e.getMessage(), e));
        }
        uploads.addAll(result.uploads);
        if (!result.truncated) {
          return caller.succeeded(uploads);
        }
        if (result.nextKeyMarker == null
          || (result.nextKeyMarker.equals(keyMarker) && Objects.equals(result.nextUploadIdMarker, uploadIdMarker))) {
          return caller.failed(new S3ClientException("ListMultipartUploads response is truncated but its next "
            + "markers don't advance", null));
        }
        if (page >= maxPages) {
          return caller.failed(new S3ClientException("More than " + (maxPages * pageSize) + " multipart uploads are "
            + "open" + (prefix == null || prefix.isEmpty() ? "" : " under this prefix") + ": list a narrower prefix", null));
        }
        return uploadsPage(caller, bucket, prefix, pageSize, result.nextKeyMarker, result.nextUploadIdMarker, page + 1,
          maxPages, uploads);
      });
  }

  // --- Requests without argument checks, for the public operations and for streamed uploads ---

  /** PutObject with a buffered body; {@code sha256Hex} if already computed, else {@code null}. */
  Future<String> putBuffered(ContextSupport.Caller caller, String bucket, String key,
                             List<Map.Entry<String, String>> headers, Buffer body, String sha256Hex) {
    return transport.execute(caller, S3Request.object(HttpMethod.PUT, bucket, key, headers, body).withPayloadHash(sha256Hex))
      .map(response -> response.headers.get(HttpHeaders.ETAG));
  }

  Future<String> createUpload(ContextSupport.Caller caller, String bucket, String key,
                              List<Map.Entry<String, String>> headers) {
    return transport.execute(caller,
        new S3Request(HttpMethod.POST, bucket, key, List.of(Map.entry("uploads", "")), headers, null))
      .compose(response -> {
        try {
          return caller.succeeded(ResponseParsers.createMultipartUpload(response.body));
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid CreateMultipartUpload response: " + e.getMessage(), e));
        }
      });
  }

  /** UploadPart, which the client only uses for streamed uploads; the Future gives the part's ETag. */
  Future<String> uploadPart(ContextSupport.Caller caller, String bucket, String key, String uploadId, int partNumber,
                            Buffer body, String sha256Hex) {
    return transport.execute(caller, new S3Request(HttpMethod.PUT, bucket, key,
          List.of(Map.entry("partNumber", Integer.toString(partNumber)), Map.entry("uploadId", uploadId)), List.of(), body)
        .withPayloadHash(sha256Hex))
      .compose(response -> {
        String etag = response.headers.get(HttpHeaders.ETAG);
        return etag == null || etag.isEmpty()
          ? caller.failed(new S3ClientException("UploadPart response without an ETag (part " + partNumber + ")", null))
          : caller.succeeded(etag);
      });
  }

  Future<String> completeUpload(ContextSupport.Caller caller, String bucket, String key, String uploadId,
                                List<S3CompletedPart> parts) {
    Buffer body;
    try {
      body = MultipartRequests.completeBody(parts);
    } catch (IllegalArgumentException e) {
      return caller.failed(new S3ClientException(e.getMessage(), e));
    }
    return sendComplete(caller, bucket, key, uploadId, body);
  }

  private Future<String> sendComplete(ContextSupport.Caller caller, String bucket, String key, String uploadId,
                                      Buffer body) {
    return transport.execute(caller,
        new S3Request(HttpMethod.POST, bucket, key, List.of(Map.entry("uploadId", uploadId)), List.of(), body))
      .compose(response -> {
        try {
          return caller.succeeded(ResponseParsers.completeMultipartUpload(response));
        } catch (S3ServiceException e) {
          return caller.failed(e);
        } catch (XmlParseException e) {
          return caller.failed(new S3ClientException("Invalid CompleteMultipartUpload response: " + e.getMessage(), e));
        }
      });
  }

  Future<Void> abortUpload(ContextSupport.Caller caller, String bucket, String key, String uploadId) {
    return transport.execute(caller,
        new S3Request(HttpMethod.DELETE, bucket, key, List.of(Map.entry("uploadId", uploadId)), List.of(), null))
      .mapEmpty();
  }

  /** A streamed upload that finished (or failed) no longer needs aborting on close. */
  void unregister(StreamingUpload upload) {
    uploads.remove(upload);
  }

  @Override
  public Future<Void> close() {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    // Unregisters first, so the owner no longer references a closed client until it closes
    return caller.bind(registration.close());
  }

  /** The registration with the owner's close future. For tests. */
  ContextSupport.Registration registration() {
    return registration;
  }

  /** Whether the client is closed (explicitly or by the close hook). For tests. */
  boolean isClosed() {
    return shutdown != null;
  }

  /**
   * The CreateBucket body: a {@code LocationConstraint} naming the client's region, or none for
   * {@code us-east-1}. Package-private for tests.
   */
  static Buffer createBucketBody(String region) {
    if (US_EAST_1.equals(region)) {
      return null;
    }
    return new XmlWriter("CreateBucketConfiguration", S3Xml.S3_NAMESPACE)
      .element("LocationConstraint", region)
      .toBuffer();
  }

  private Future<Void> bucketRequest(HttpMethod method, String bucket, Buffer body) {
    ContextSupport.Caller caller = ContextSupport.capture(vertx);
    return call(caller, BucketNames.problem(bucket, config),
      () -> transport.execute(caller, S3Request.bucket(method, bucket, body))).mapEmpty();
  }

  private Future<S3ObjectStream> objectStream(ContextSupport.Caller caller, String bucket, String key, String problem,
                                              List<Map.Entry<String, String>> headers) {
    return call(caller, problem, () -> transport.stream(caller, S3Request.object(HttpMethod.GET, bucket, key, headers, null))
      .map(response -> new ObjectStreamImpl(vertx, caller, response, ObjectRequests.metadata(response.headers()))));
  }

  private String objectProblem(String bucket, String key) {
    String problem = BucketNames.problem(bucket, config);
    return problem != null ? problem : ObjectRequests.keyProblem(key);
  }

  private String uploadProblem(String bucket, String key, String uploadId) {
    String problem = objectProblem(bucket, key);
    return problem != null ? problem : MultipartRequests.uploadIdProblem(uploadId);
  }

  /**
   * Runs {@code operation} unless the client is closed or {@code problem} (an argument check)
   * is set; either way the result is bound to the caller's context.
   */
  private <T> Future<T> call(ContextSupport.Caller caller, String problem, Supplier<Future<T>> operation) {
    if (shutdown != null) {
      return caller.failed(new S3ClientException("The S3 client is closed", null));
    }
    if (problem != null) {
      return caller.failed(new S3ClientException(problem, null));
    }
    return caller.bind(operation.get());
  }

  /**
   * Aborts the streamed uploads in progress, one after the other, then closes the transport;
   * exactly once, and every caller gets the same shutdown Future.
   */
  private Future<Void> shutdown() {
    Future<Void> current = shutdown;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (shutdown == null) {
        Future<Void> aborted = null;
        for (StreamingUpload upload : List.copyOf(uploads)) {
          aborted = aborted == null ? upload.abortOnClose() : aborted.transform(ar -> upload.abortOnClose());
        }
        shutdown = aborted == null ? transport.close() : aborted.transform(ar -> transport.close());
      }
      return shutdown;
    }
  }
}
