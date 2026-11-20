# vertx-s3-client revamp — Analysis, Plan, and AGENTS.md proposal

## Context
Total revamp of `object-storage-client` (renaming to `vertx-s3-client`), consumed by a pure Vert.x 5.2.x backend on DigitalOcean Kubernetes (Spaces in prod, MinIO in dev). Hard requirement: idiomatic Vert.x with strict context correctness. This step was **analysis only**. The only action after approval is writing `AGENTS.md` and `CLAUDE.md` at the repo root. Nothing is committed.

Branch check: `git branch` shows `* revamping`, so you are on the right branch.

---

## Build & test state (run on a scratchpad copy; the repo's `target/` was not touched)
Environment: JDK 25.0.5.1, Maven 3.10.1, Docker Engine 29.9.3 (API 1.56, min 1.40).

- `mvn -q -DskipTests compile` → **OK** (exit 0, no warnings).
- `mvn test` → **FAIL**: `S3ClientTest.beforeAll:53 » IllegalState Could not find a valid Docker environment`. XmlBodyTest: 4/4 pass.
  - Root cause (verified): Testcontainers 1.21.3 / docker-java default to API 1.32. Docker 29 rejects anything below its minimum (1.40 here). Re-running with `-Dapi.version=1.44` → **BUILD SUCCESS, 23 tests, 0 failures, 1 skipped** (`testDeleteObjectBucket` is `@Disabled`).
  - CI will fail the same way on any runner with Docker ≥ 29. Fix: upgrade Testcontainers to a Docker-29-compatible release (2.x) instead of relying on the property.

---

## Step 2 — Analysis report

### 1. Architecture overview
- **Packages:** `io.github.emadalblueshi.objectstorage.client` (an abstract `ObjectStorageClient` with only `void close()`, plus `ObjectStorageRequestOptions`), `…client.s3` (public API, about 25 types), `…client.s3.impl` (6 classes), `examples` (docgen snippets). Main code is about 2.3k LOC.
- **Entry point:** `S3Client.create(vertx, S3ClientOptions)` → `S3ClientImpl` (S3Client.java:19-21). It wraps one `WebClient` built from `S3ClientOptions.getWebClientOptions()` (S3ClientImpl.java:51).
- **Request building:** every method takes `(XxxOptions, String path[, body])`. The `path` is a raw `"/bucket/key"` that the caller builds; it is never encoded and there is no bucket/key model. The options objects are mutable `MultiMap` bags of headers and query params (`ObjectStorageRequestOptions`).
- **Signing:** `S3ClientImpl.request()` (S3ClientImpl.java:273-298) calls the static `S3SignatureV4.sign(...)`. That call **mutates the caller's options `MultiMap`** (adds host, x-amz-date, x-amz-content-sha256, authorization). Those headers are then copied into a `WebClient` `HttpRequest`. The payload is always hashed in full: `sha256(body.getBytes())`.
- **Sending:** `sendBuffer` / `send` / `sendJsonObject` on WebClient. Every response body is buffered in memory.
- **Response mapping:** `toS3Response(Class)` (S3ClientImpl.java:309-347). A status >300 and <600 means the error XML is parsed with Jackson into `S3ResponseException`. Otherwise Jackson XML maps the body into the model, or returns it as a `Buffer` or `null`. The result is an `S3ResponseImpl`, which **extends `io.vertx.ext.web.client.impl.HttpResponseImpl`**, an internal web-client class (S3ResponseImpl.java:10,16).
- **Multipart:** `putObjectAsStream` / `putObjectFileAsStream` use `S3BufferQueueReadStream` to re-chunk any `ReadStream<Buffer>` into fixed 5 MiB parts and upload them one at a time.

### 2. Vert.x usage
- Vert.x **5.0.4** (pom.xml:33). Target: 5.2.x (5.2.3 is in the local repo).
- **Style:** Future-only API, with no callback overloads and no `CompletableFuture`. The exception is `close()`, which is `void` and synchronous (ObjectStorageClient.java:10, S3ClientImpl.java:227-229).
- **Transport:** WebClient. It adds little value here (no codecs used), and it gets in the way of streaming downloads and streaming request bodies. Recommendation: use `HttpClient` / `HttpClientAgent` directly. Request bodies then go through `HttpClientRequest.send(ReadStream)` or a manual `pipeTo`, and responses arrive as `HttpClientResponse` (a ReadStream).
- **Streaming:** upload only, via a custom re-chunking ReadStream with hand-rolled backpressure (buggy, see §3/§5). There is **no download streaming**: `getObject` buffers the whole object.
- **Codegen:**
  - `@VertxGen` on `S3Client`, `S3Response`, `ObjectStorageClient` and the enums. It is pointless: no language or Rx generators are configured, and `S3Response<T> extends HttpResponse<T>` is borderline codegen-legal.
  - `@DataObject` + `@JsonGen` on every options and model class. This works through `maven-processor-plugin`, with `vertx-docgen-processor` 0.9.8 (stale) added on top.
- **Changes for 5.2.x:**
  - Bump the BOM.
  - Drop the dependency on `web.client.impl.HttpResponseImpl`.
  - `close()` → `Future<Void>`.
  - Add a context close hook.
  - Align Jackson versions or drop Jackson (see §6).
  - Remove `@VertxGen` and docgen.
  - Keep `@DataObject`/`@JsonGen` only for options, run through `annotationProcessorPaths` instead of maven-processor-plugin.

### 3. Context audit (top-priority finding)
**Verdict:** single-shot calls are correct *by accident*. Vert.x 5's WebClient/HttpClient captures `vertx.getOrCreateContext()` when the request is made and completes its future there, and `compose` keeps that context. The library itself captures and enforces nothing. Multipart, stream handling and blocking work violate the rules:

| # | Location | Problem |
|---|---|---|
| C1 | S3ClientImpl.java:160, 190, 311 | `Promise.promise()` is not bound to a context. Listeners run on whichever thread completes it. For multipart (160, 190) that is the thread that delivered the last part or complete callback, which may not be the caller's context. 311 is saved only because it is wrapped in `compose`. |
| C2 | S3ClientImpl.java:156-183 | `putObjectAsStream` never captures the caller's context. Every stream callback (`handler`/`endHandler`/`exceptionHandler`) runs on **the user stream's owning context**. If that differs from the caller's context (for example an `HttpServerRequest` from another verticle), the whole multipart chain, including `ArrayList s3Parts` and the final future, runs on a foreign context. Nothing checks this or adapts to it. |
| C3 | S3ClientImpl.java:165 (with 158) | The user's `ReadStream` is paused **after** the async `initiateMultipart` round-trip. Until then the stream flows with no handler, so data can be **dropped** (for example from an `HttpServerRequest` body). This is a correctness and data-loss bug as well as a backpressure bug. |
| C4 | S3BufferQueueReadStream.java:94-132 | `handleChunks()` emits several chunks in a `while` loop inside one upstream callback and ignores `pause()`. That breaks the ReadStream contract, and part uploads then run concurrently with the shared `partNumber.get()` (S3ClientImpl.java:171, 204): wrong part↔ETag mapping. |
| C5 | S3BufferQueueReadStream.java:65-67, 37-54, 134 | `endHandler` is only fired from `resume()`/`fetch()`, never from the upstream end. An **empty stream hangs forever**. A size that is an exact multiple of 5 MiB hangs whenever end is delivered after the last resume. `handleEnd` can fire more than once. |
| C6 | S3SignatureV4.java:39 and S3ClientImpl.java:288 | Full-body SHA-256 runs **on the event loop**: the whole `putObject` body, and every 5 MiB part. `body.getBytes()` also copies the whole buffer. `HashUtil.hex` uses `String.format` per byte (HashUtil.java:19). `MessageDigest`/`Mac.getInstance` is looked up on every call (HashUtil.java:26, 37). |
| C7 | S3ClientImpl.java:314, 327 | Jackson XML (de)serialization inline on the caller thread. That is fine for small payloads, but large ListObjects / ACL bodies would be heavy CPU on the event loop. |
| C8 | S3ClientImpl.java:109, 282 | Synchronous throws from public async methods. `putBucket` wraps `JsonProcessingException` in `RuntimeException`, and NPEs come from null options or args. Async methods must return a failed Future instead. |
| C9 | S3ClientImpl.java:227-229 | `close()` is `void`, has no close hook, and doesn't track in-flight operations. Undeploying a verticle mid-multipart leaves orphaned uploads. |
| C10 | S3ClientImpl.java:280-282, 67 | Shared mutable state: the caller's options `MultiMap` is mutated during signing (and `copyObject` mutates it too). Reusing one options instance concurrently from two contexts is a data race, and reusing it sequentially breaks the signature (see §5). |
| C11 | Tests | Tests call `readFileBlocking` / `openBlocking` on the event loop (S3ClientTest.java:138, 176, 390, 412) and share a `static S3Client` across tests. No test checks contexts. |

No own threads, executors, `CompletableFuture`, or static `Vertx` instances exist. That part is good.

**Internal vs public API: recommend narrow, justified use of `io.vertx.core.internal`.**
- Use `ContextInternal ctx = (ContextInternal) vertx.getOrCreateContext()` at call time, then `ctx.promise()` (a `PromiseInternal` bound to ctx, so completion is dispatched to ctx from any thread).
- Use `ctx.addCloseHook(Closeable)` / `removeCloseHook` for verticle-scoped cleanup. There is no public equivalent. Vert.x's own pg/mail/redis clients do exactly this.
- Confine it to one package-private helper in `impl`, and let the context-assertion test suite catch any internal-API drift on Vert.x upgrades.
- The public-only alternative is possible: capture the `Context`, then `ctx.runOnContext` when not `Vertx.currentContext()==ctx`. It is boilerplate-heavy and still cannot provide close hooks.

### 4. S3 API coverage
- **Present:**
  - Objects: PutObject (Buffer), GetObject (buffered), DeleteObject, HeadObject (`ObjectInfo`), CopyObject, GetObjectAcl (`ObjectAcl`).
  - Buckets: CreateBucket (with and without configuration), DeleteBucket, Get/Put/DeleteBucketPolicy, GetBucketPolicyStatus.
  - Multipart: hidden inside the stream upload; initiate, upload part and complete are private.
- **Missing (important for you):**
  - **ListObjectsV2** (the list query options exist in `S3BucketOptions`, but no operation uses them).
  - **DeleteObjects (batch)**.
  - **HeadBucket**.
  - **Public multipart API** with **AbortMultipartUpload**, ListParts and ListMultipartUploads.
  - **UploadPartCopy** (server-side copy > 5 GB).
  - **Streaming GetObject** (ReadStream / pipeTo WriteStream / to file).
  - **Ranged GET** that works (see §5).
  - **Presigned URLs** (GET/PUT).
  - Get/PutObjectTagging.
  - ListBuckets.
  - Conditional PUT (`If-None-Match: *`).
  - Retries.
- **Dead option surface:** `append`, `position`, `retention`, `legalHold`, `versioning`, `objectLock`, `notification`, `location`, `keyMarker`/`maxUploads`/`uploadIdMarker`, `S3Bucket`/`S3Location` (AWS directory buckets) and `S3SignatureVersion` (only `V4`) have no operation behind them.

### 5. Correctness & security risks
- **Canonical query is not sorted** (S3SignatureV4.java:34-36). Requests with more than one query param only work if the caller adds them in sorted order. The comment at S3ClientImpl.java:243 shows the workaround. The canonical query is encoded by Netty's `QueryStringEncoder`, while the wire query is encoded independently by WebClient; any encoding mismatch means `SignatureDoesNotMatch`.
- **Query params use a case-insensitive MultiMap and `set` instead of `add`** (ObjectStorageRequestOptions.java:21, 47). S3 query keys are case-sensitive.
- **Canonical URI is not encoded** (S3SignatureV4.java:55) and the path is sent raw. Keys with spaces, `+`, `%`, unicode, `?` or `#` break or are misrouted. The rule should be: S3 single-encoding, keep `/`, RFC 3986 unreserved set.
- **Canonical headers:**
  - Keys come from `entries()` and are not de-duplicated, so multi-value headers are listed twice.
  - Only the first value is used: `headers.get(key)` (S3SignatureV4.java:46-58).
  - Whitespace is not collapsed.
  - Every header is signed, including anything the user adds.
- **Reusing an options object breaks signing.** The `authorization` header from the previous request stays in the options `MultiMap`, gets signed into the next canonical request with its *old* value, and the server rejects the request. `copyObject` permanently adds `x-amz-copy-source` to the caller's options.
- **Host is signed without the port** (S3SignatureV4.java:42, with the host taken from `getDefaultHost()` at S3ClientImpl.java:283) and forced into the Host header. It works against MinIO on a mapped port only because both sides agree. Evidence: MinIO returns `Location: http://localhost/demo-bucket/...` with the port missing (S3ClientTest.java:380). This **will break presigned URLs**: a browser sends `host:port`.
- **Endpoint model:**
  - Path-style only, implicitly; there is no virtual-hosted style.
  - There is no endpoint URL. Scheme, host and port come from `WebClientOptions`.
  - Region defaults to the magic value `"default"` (S3ClientOptions.java:16).
  - The README uses non-existent `setHost`/`setPort` (README.adoc:25-26).
  - For Spaces you need endpoint `https://<slug>.digitaloceanspaces.com` plus an explicit region. Path-style and virtual-host style both work there.
  - For presign you also need a separate **public endpoint** (in-cluster `minio:9000` vs `localhost:9000` in the browser).
- **Payload hashing:** always full-body and in memory. There are no `UNSIGNED-PAYLOAD` or `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` options, and no `Content-MD5` / checksum support. Recommendation:
  - Make the strategy configurable.
  - For multipart parts, hash **incrementally as chunks arrive**: `MessageDigest.update` per ~64 KB chunk costs microseconds, so nothing blocks and no `executeBlocking` is needed.
  - For a large caller-supplied `Buffer`, use `executeBlocking` above a threshold, or `UNSIGNED-PAYLOAD` over TLS.
  - Do **not** send the new AWS default trailing checksums (`x-amz-checksum-*` / `aws-chunked`). Several S3-compatibles reject them.
- **Credentials:**
  - Default credentials are `"accesskey"`/`"secretkey"` (S3AuthOptions.java:16-17); the client should fail fast instead.
  - The `@JsonGen` converter serializes `secretKey` in `toJson`.
  - No session token (`x-amz-security-token`).
  - No rotation or provider abstraction (k8s Secret rotation).
  - No redaction rule. `setLogActivity(true)` in tests dumps `Authorization`.
- **Error mapping:**
  - `>300` treats 300 as success and every 3xx as an XML error.
  - Bodiless errors (HEAD 404, 304, 412) make Jackson throw `MismatchedInputException` instead of an S3 error, and the status code is lost.
  - Non-XML errors (proxy/LB HTML 502/503) are mis-mapped the same way.
  - `S3ResponseException` carries no status, hostId or cause.
- **Multipart:**
  - No `AbortMultipartUpload` on any failure path, so **orphaned parts are billed on Spaces**.
  - The `AsyncFile` is **never closed on success** (S3ClientImpl.java:214-216): an FD leak.
  - The file is opened *after* initiate (S3ClientImpl.java:192), so a missing file leaves an orphaned upload.
  - Part size is fixed at 5 MiB, which caps objects at about 48.8 GiB (10k parts).
  - `promise.fail` (not `tryFail`) can throw `IllegalStateException` (S3ClientImpl.java:174, 207).
  - An empty stream would start an invalid zero-part multipart; it should fall back to PutObject.
- **Option bugs:**
  - `range()` emits `"0-100"` without the `bytes=` prefix (S3ObjectOptions.java:73-75).
  - `ifUnmodifiedSince` sets `if-modified-since` (S3ObjectOptions.java:83-85).
  - `putHeaders(MultiMap headers)` / `addQueryParam(MultiMap)` shadow the field, so they are self-adds and the options are never updated (ObjectStorageRequestOptions.java:41-44, 56-59).
  - `S3Acl.PUBLIC("public")` is not a valid canned ACL (S3Acl.java:11).
  - `putBucketPolicy` signs `policy.toBuffer()` but sends `sendJsonObject(policy)` (S3ClientImpl.java:135-139). This is fragile: the signature only holds if both encodings produce identical bytes.
- **No retries, timeouts or clock-skew handling.**

### 6. Code quality
- **API:**
  - Inconsistent naming: `ObjectInfo`, `ObjectAcl` (capitalized), `putObjectAsStream`, `putBucket`.
  - Options go in the first parameter.
  - Bucket and key are hidden in a raw path string.
  - The response type leaks WebClient's `HttpResponse` (cookies, followedRedirects, `bodyAsJsonArray`).
  - `contentLength()` returns `String`.
  - `@CacheReturn` is on `requestId` for no reason.
- **Options:**
  - Mutable header bags with a casting-heavy covariant-override pattern (`(S3ObjectOptions) super.putHeader(...)`) repeated in three classes.
  - The `S3ClientOptions` copy constructor is shallow (S3ClientOptions.java:33-38).
  - `S3AuthOptions` has no copy of its own JSON support and no `toJson`.
  - `ObjectStorageRequestOptions` claims `@DataObject` but its JSON converter cannot round-trip `MultiMap`.
- **Error model:** a single `S3ResponseException`, plus raw Jackson exceptions leaking out.
- **Null handling:** nothing is validated; `Objects.requireNonNull` appears only in the constructor.
- **Logging:** none. That is acceptable, but there is no debug path either.
- **Dependencies:**
  - Jackson **version skew**: `jackson-dataformat-xml` 2.16.1 sits next to databind/core 2.18.2 from the Vert.x BOM.
  - XML pulls in woodstox + stax2 + jsr310 + databind.
  - Recommendation: replace them with **JDK StAX** (`java.xml`, zero deps, DTD/external entities disabled) plus a small hand-written XML writer. S3's XML is trivial.
- **Dead code:**
  - The `ObjectStorageClient` abstraction (a "SWIFT someday" layer, see index.adoc).
  - `CephRadosGatwayContainer` (unused, image tag `latest`).
  - `ObjectStorageClientOptionsTest` (empty).
  - Test resources `endpoints`, `user.json`, `group.json`, `service-account-credentials.json`, `set-policy-multi.json` and most likely `bucket.json` (MinIO admin leftovers, unreferenced).
  - The dead options listed in §4.
- **Duplication:** `putObjectAsStream` and `putObjectFileAsStream` are near copies. `S3ResponseImpl` overrides 15 methods only to call `super`.

### 7. Tests
- `S3ClientTest`:
  - One ordered, stateful integration suite against MinIO (`RELEASE.2025-03-12…`) via a custom `GenericContainer`.
  - A static client and order-dependent tests (`@Order`).
  - Mostly asserts headers and status codes.
- `XmlBodyTest`: 4 Jackson mapping unit tests.
- **Gaps:**
  - No SigV4 unit tests (no AWS test vectors).
  - No context assertions at all.
  - No worker or virtual-thread verticles.
  - No backpressure, empty-stream, exact-5-MiB, failure or abort tests.
  - No special-character keys.
  - No error mapping for HEAD/304.
  - No tests of concurrent operations.
  - No Spaces smoke test.
  - Integration tests run under surefire even though failsafe is configured, and there are no `*IT` classes.
  - The large-image test file (12.7 MB, three parts) is committed to the repo.

### 8. Build & CI
- **pom:**
  - `release 11` (pom.xml:300) while javadoc uses `<source>8</source>` (pom.xml:271).
  - `doclint none`.
  - maven-processor-plugin + docgen + asciidoctor site.
  - A **test-jar is attached and published** to Central (it ships test containers).
  - Unused build-helper plugin.
  - No enforcer.
  - No formatter (spotless) despite `.editorconfig`.
  - central-publishing-maven-plugin 0.7.0 with a gpg profile.
  - Plugin versions are mid-2024.
- **CI:**
  - `actions/setup-java@v3` (outdated).
  - No Maven cache.
  - JDK matrix 11/21.
  - Job names still say **OSSRH** (sunset; the Central Portal is actually used).
  - Releases trigger on **any** tag (`'*'`).
  - Snapshot deploy needs Central Portal snapshots enabled for the namespace.
  - Tests will fail on Docker 29 runners (see the Testcontainers issue above).
  - No `concurrency` group and no `permissions:` block.

### 9. Docs
- README:
  - The example uses `WebClientOptions.setHost/setPort`, which don't exist; `Examples.java` correctly uses `setDefaultHost`.
  - Uses `readFileBlocking` and `openBlocking` in the example.
  - Callback-style `onComplete` boilerplate.
  - Doesn't mention that paths must include the bucket and be pre-encoded.
  - Says nothing about Vert.x version, Java version or Maven coordinates.
- `index.adoc` promises SWIFT/Ceph/Garage generality that the code doesn't have.
- The CI badge points at the old repo name.

### 10. Rename inventory
| Where | Current value |
|---|---|
| pom.xml:7 | `<artifactId>object-storage-client` |
| pom.xml:11-13 | name `Object Storage Client`, description, `url …/object-storage-client` |
| pom.xml:268 | javadoc `io/github/emadalblueshi/**` (stays if groupId kept) |
| pom.xml:380-382 | scm connection/developerConnection/url `…/object-storage-client(.git)` |
| package-info.java:1 | `@ModuleGen(name = "object-storage-client", groupPackage = "io.github.emadalblueshi")` |
| META-INF/MANIFEST.MF:1 | `Automatic-Module-Name: io.github.emadalblueshi.objectstorage.client` (no module-info.java exists) |
| All 37 main + 5 test Java files | package `io.github.emadalblueshi.objectstorage.client[.s3[.impl]]`, `…objectstorage.container` |
| Class names | `ObjectStorageClient`, `ObjectStorageRequestOptions`(+ generated `…Converter`), `ObjectStorageClientOptionsTest` |
| Directory trees | `src/{main,test}/java/io/github/emadalblueshi/objectstorage/**` |
| README.adoc:1, 4 | title, CI badge + link `EmadAlblueshi/object-storage-client` |
| src/main/asciidoc/index.adoc:1-3 | title / "object storage client" prose |
| asciidoctor outputDir | `${project.artifactId}` (follows automatically) |
| ci.yml | no literal name (owner checks only) — unaffected |
| GitHub | repository name `EmadAlblueshi/object-storage-client` |

**Proposed coordinates:**
- **groupId: keep `io.github.emadalblueshi`.** The namespace is already verified on Central. Changing it means re-verifying, and gains nothing.
- **artifactId:** `vertx-s3-client`.
- **Root package:** `io.github.emadalblueshi.vertx.s3`, with `…vertx.s3.impl` for internals.
  - Alternative: `io.github.emadalblueshi.s3`, which is shorter but loses the Vert.x signal.
  - Never `io.vertx.*`.
- **JPMS:** a real `module-info.java` named `io.github.emadalblueshi.vertx.s3`, exporting only the API package. Alternatively keep just `Automatic-Module-Name`.
- **Trade-off on the name:** a third-party `vertx-` prefix is common practice in the ecosystem. Just don't brand it as an official Eclipse Vert.x module.
- **Old artifact:** don't publish a relocation POM. The package changes too, so a relocation would only produce compile errors.

---

## Step 3 — Revamp plan

**Rewrite vs refactor (opinion):**
- Signer → **rewrite** (test-vector driven).
- Client core → **rewrite** on HttpClient.
- Upload streaming → **rewrite**.
- Options and model → **rewrite**: immutable-ish request types, no header-bag inheritance.
- XML → **rewrite** on StAX.
- Errors → **rewrite**.
- Tests → **rewrite**, but keep the MinIO container idea.
- Build → **refactor**.
- CI → **refactor**.
- Docs → **rewrite**.

In effect this is a greenfield rewrite in the new package. Delete the old package rather than porting it.

**On renaming first:** I agree, with one change. Don't mechanically rename the old code; it is all being replaced, so a rename would be wasted churn. Make Phase 1's first commit the new coordinates plus the new empty package skeleton, and delete the old tree in the same PR. There is no published dependent to protect, so renaming before the first new release costs nothing. Rename the GitHub repo in Phase 1 too (GitHub redirects old URLs).

| Phase | Goals | Concrete changes | Risks |
|---|---|---|---|
| **0. Groundwork** (this step) | Rules in place | AGENTS.md + CLAUDE.md | — |
| **1. Rename + skeleton** ⚠️BREAKING | New identity, green build | pom coordinates; delete `objectstorage` tree; new package + `module-info`; Vert.x 5.2.x BOM; Java baseline; Testcontainers 2.x; drop processor-plugin/docgen/asciidoctor/test-jar; codegen-json via `annotationProcessorPaths`; enforcer + spotless; CI: setup-java v4, cache, matrix on the chosen JDK(s), tag pattern `v*`, permissions; **context-test harness first** (event-loop / worker / virtual-thread verticle fixtures + `assertSameContext` helper) | Internal-API choice must be settled first |
| **2. Signer + core ops** ⚠️BREAKING | Correct SigV4, idiomatic core | `S3ClientOptions` (endpoint URI, region required, pathStyle, credentials provider, payload strategy, timeouts) with copy ctor + toJson/fromJson; SigV4 signer (sorted/encoded query, RFC3986 path, canonical headers, host:port, session token) unit-tested with AWS SigV4 test-suite vectors; HttpClient transport; context-bound promises; `S3Exception` hierarchy (status, code, requestId, hostId; bodiless + non-XML handling); StAX XML; ops: head/get(Buffer)/put(Buffer)/delete/copy object, create/head/delete bucket, ListObjectsV2 (+ paging helper), DeleteObjects | Encoding edge cases; Spaces quirks |
| **3. Streaming** | Backpressure-correct I/O | GetObject → `ReadStream<Buffer>` / `pipeTo(WriteStream)` / to file (AsyncFile); upload(ReadStream) with pause-before-async, incremental part hashing, configurable part size (≥5 MiB), single-PUT fallback for small/empty, **AbortMultipartUpload on every failure**, file close on all paths; uploadFile; public multipart ops (create/uploadPart/complete/abort/listParts) | Hidden backpressure bugs → test empty, exact-multiple, 1-byte-over, slow-consumer, foreign-context stream |
| **4. Presigned URLs** | GET/PUT presign | Query-signed URLs, expiry 1s–7d, `UNSIGNED-PAYLOAD`, optional content-type/response-header overrides, **separate public endpoint** option; tests: presign then fetch via plain HttpClient against MinIO (including non-default port) | Host/port mismatch, clock skew |
| **5. Hardening** | Production-ready on DOKS | Retries with backoff via Vert.x timers (5xx, SlowDown, connection reset; idempotent only), per-op timeouts, RequestTimeTooSkewed handling, close hook + in-flight tracking (abort uploads on close), log redaction rules, optional bucket policy/ACL/tagging ops, opt-in Spaces smoke test in CI with secrets | Internal API drift on Vert.x bumps |
| **6. Docs + release** | First release `vertx-s3-client` | README rewrite (Spaces + MinIO config, context guarantees), javadoc with doclint on, CHANGELOG, Central release `0.5.0` (or `1.0.0-alpha1`) | — |

---

## Recorded decisions (2026-11-20) — supersede the questions below
1. Coordinates: `io.github.emadalblueshi:vertx-s3-client`, package/module `io.github.emadalblueshi.vertx.s3`.
2. Java: main **and test** code compile with `--release 11` (no `testRelease`). The JDK 21 CI job runs the full suite including `@Tag("virtual-threads")`. A `java11` profile auto-activates on JDK 11 and only excludes that tag.
3. `ContextInternal`/`PromiseInternal`/close hooks are allowed only in `impl` packages. Vert.x is pinned, and internal usage is re-verified on every upgrade.
4. Configurable payload signing. Defaults: UNSIGNED-PAYLOAD for streaming/multipart over HTTPS; signed SHA-256 for small buffered requests, hashed via `executeBlocking` above a threshold; signed payloads only over HTTP. The full matrix is tested against MinIO.
5. Single Maven module.
6. JDK StAX replaces Jackson XML. The parser is hardened and writes are escaped.
7. `HttpClient` directly. `vertx-core` is the only runtime dependency.
8. Keep `@DataObject` plus generated JSON converters for options.

### Phase 1 — additional concrete items
- **Testcontainers 2.0.5.** Verified 2026-11-20: the current suite passes against Docker 29 without `-Dapi.version`. Java 8 bytecode, so it works on the JDK 11 job.
- **JUnit:** import `junit-bom` **5.14.4** in `dependencyManagement` *before* `vertx-dependencies`. The Vert.x 5.2.3 BOM manages JUnit Jupiter 6.0.0, which is Java 17 bytecode and would break the JDK 11 job. vertx-junit5 5.2.3 (Java 11 bytecode) is built against JUnit 5.14.0.
- **Pin Vert.x** to an exact version (5.2.3). The only runtime dependency is `vertx-core`. Remove `vertx-web-client`, Jackson (databind, dataformat-xml, jsr310) and `vertx-docgen-processor`.
- **Compiler:** `maven.compiler.release=11` for main and test. Codegen-json runs via `annotationProcessorPaths`, replacing maven-processor-plugin.
- **Surefire:** a `java11` profile (`<activation><jdk>11</jdk></activation>`) sets `excludedGroups=virtual-threads`, and nothing else.
- **CI:** two jobs. JDK 21 runs `mvn -B verify` (full suite). JDK 11 runs `mvn -B verify` (the profile excludes the VT tag). Also: setup-java v4 with the maven cache, `permissions: contents: read`, a release tag pattern `v*`, job names without "OSSRH". No `-Dapi.version` anywhere.
- **TLS test fixture:** a committed self-signed certificate and key under `src/test/resources`, named as test-only (e.g. `test-only-minio-selfsigned.{crt,key}`), with a long expiry (~100 years) and SANs `localhost` and `127.0.0.1`. It's mounted into the MinIO container for the HTTPS half of the payload-signing matrix.
- The context-test harness (event-loop, worker and virtual-thread verticle fixtures plus an `assertSameContext` helper) is built first, with the VT fixture tagged `virtual-threads`.

## Decision questions for you (answer before Phase 1)
1. **Coordinates/package:** keep groupId `io.github.emadalblueshi`, root package `io.github.emadalblueshi.vertx.s3`, module `io.github.emadalblueshi.vertx.s3`? (alt: `io.github.emadalblueshi.s3`)
2. **Minimum Java:** 21 (I recommend it: needed for virtual-thread verticle tests, plus records, sealed exceptions, `HexFormat`) or 17? And should CI also test 25?
3. **Internal Vert.x APIs:** allow `ContextInternal`/`PromiseInternal`/close hooks, confined to `impl` (I recommend it), or public API only (no close hooks)?
4. **Large-payload signing:** default to signed payloads with incremental part hashing plus `executeBlocking` above a threshold for big Buffers (I recommend it), or `UNSIGNED-PAYLOAD` over HTTPS by default? Is `aws-chunked` streaming signing wanted at all?
5. **Module layout:** a single module (I recommend it), or core plus a separate `vertx-s3-client-testing` module with the MinIO container helpers?
6. **XML:** drop Jackson for JDK StAX (I recommend it), or keep Jackson XML with versions aligned to the BOM?
7. **Transport:** HttpClient directly (I recommend it), or keep WebClient?
8. **Codegen:** keep `@DataObject` + `@JsonGen` converters for options (I recommend it), or hand-write `toJson`/`fromJson`?

---

## Step 4 — Files to write on approval (nothing else; no commit)

### `/home/emad/Projects/java/object-storage-client/CLAUDE.md`
```
@AGENTS.md
```

### `/home/emad/Projects/java/object-storage-client/AGENTS.md`
Items marked *(pending decision)* use my recommended defaults until you answer the questions above.

````markdown
# AGENTS.md — vertx-s3-client

## Purpose
Asynchronous, idiomatic Vert.x 5 client for Amazon S3 and S3-compatible storage
(DigitalOcean Spaces in production, MinIO for local dev/tests). Consumed by a pure
Vert.x 5.2.x backend running on Kubernetes.

**Rename in progress:** formerly `object-storage-client`
(`io.github.emadalblueshi.objectstorage.client`). The old API is being replaced, not
migrated; backward compatibility is NOT a goal. Do not add code to the old package.

## Tech stack
- Java 21 *(pending decision)*, Maven
- Vert.x 5.2.x (`vertx-core`, `HttpClient`) *(transport pending decision)*
- `vertx-codegen` (`@DataObject` / `@JsonGen`) for options classes, provided scope
- Tests: JUnit 5, `vertx-junit5`, Testcontainers (MinIO)

## Coordinates & layout *(pending decision)*
- Maven: `io.github.emadalblueshi:vertx-s3-client`
- Module: `io.github.emadalblueshi.vertx.s3` (exports the API package only)
- `io.github.emadalblueshi.vertx.s3` — public API (client interface, options, results, exceptions)
- `io.github.emadalblueshi.vertx.s3.impl` — internals (signer, transport, XML, streams). Never exported.

## Commands
- Compile: `mvn -q -DskipTests compile`
- Unit + integration tests: `mvn verify` (needs a running Docker for Testcontainers)
- Single test: `mvn test -Dtest=ClassName#method`
- Format check: `mvn spotless:check` (once configured)

## Vert.x rules (NON-NEGOTIABLE)
1. **Future-only public API** (Vert.x 5 style). No callback overloads, no
   `CompletableFuture`, no blocking methods in the public API.
2. **Context capture:** every public async method captures the caller's context at call
   time (`vertx.getOrCreateContext()`; `ContextInternal` only inside `impl`, where justified)
   and completes its Future ON THAT CONTEXT, whether the caller is an event loop, a worker,
   or a virtual-thread verticle. Never use an unbound `Promise.promise()` for a result that
   crosses an async boundary.
3. **Streams:** `ReadStream`/`WriteStream` handlers (`handler`, `endHandler`,
   `exceptionHandler`, `drainHandler`) run on the owning context, with real backpressure
   (`pause`/`resume`/`fetch`, `pipeTo`). Pause any user-supplied stream BEFORE the first
   async step. Never emit while paused.
4. **Never block the event loop:** no blocking file I/O (use `FileSystem`/`AsyncFile`),
   no `*Blocking` calls, no heavy inline CPU. Hash large payloads incrementally per chunk or
   via `executeBlocking`, or use an unsigned/streaming payload strategy.
5. **No own threads, executors, or static `Vertx`.** The client is bound to the `Vertx`
   passed in. `close()` returns `Future<Void>`. A client created inside a verticle is
   closed when that context closes (close hook).
6. **Prefer Vert.x primitives:** `HttpClient`, `Buffer`, `Future` composition
   (`compose`, `transform`, `eventually`, `recover`), Vert.x timers for retries/timeouts,
   JsonObject-backed options classes with a copy constructor plus `toJson`/`fromJson`.
7. Public async methods never throw; validation failures return a failed Future.

## S3 conventions
- Path-style and virtual-hosted-style are both supported. The endpoint is a URI, the region is required
  (no magic defaults). A separate public endpoint is allowed for presigned URLs.
- SigV4: sorted, RFC 3986-encoded canonical query; S3 single-encoded path; Host includes
  a non-default port; never sign the stale `Authorization` header; request options are immutable
  per call and never mutated by the client.
- Multipart: abort on every failure path; close every opened `AsyncFile`; an empty or small body
  uses a single PutObject.
- Don't send AWS-only trailing checksums by default. They must work against Spaces/MinIO.
- Errors map to the library's `S3Exception` hierarchy (HTTP status, S3 code, requestId);
  handle bodiless (HEAD) and non-XML error responses.

## Security
- Never log credentials, `Authorization`, `x-amz-security-token`, or presigned URLs.
- Options `toJson()`/`toString()` must not expose the secret key.
- XML parsing has DTDs and external entities disabled.
- No default credentials; fail fast when they are missing.

## Testing rules
- vertx-junit5. Integration tests run against MinIO via Testcontainers (pinned image tag).
- Every public async operation has a context test: from an **event-loop verticle**, a
  **worker verticle**, and a **virtual-thread verticle**, assert that
  `Vertx.currentContext()` inside every callback / Future completion is the same instance as
  the context captured before the call.
- Streaming tests cover backpressure (slow consumer), empty stream, exact part-size multiples,
  failures, and abort.
- The signer has unit tests against the AWS SigV4 test vectors (no Docker).
- No `*Blocking` calls on event-loop threads in tests. Tests are independent (no ordering, no
  shared static client state across test classes).

## Branch & commit conventions
- Work on feature branches off `revamping` (or `master` after the merge). Never commit to `master` directly.
- Small, focused commits, imperative subject (≤72 chars), body explains why.
- Mark breaking API changes with `BREAKING:` in the commit body.

## Do / Don't for AI agents
**Do**
- Read this file and the relevant code before editing. Keep changes scoped to the task.
- Add or adjust tests (including context tests) with every behavior change. Run `mvn verify`.
- Prefer deleting dead code over preserving it. There are no compatibility obligations.
- Ask before adding a dependency, using a new `io.vertx.core.internal` API, or changing
  public API shape.

**Don't**
- Don't introduce callbacks, `CompletableFuture`, blocking calls, threads, executors,
  or static state.
- Don't use WebClient/web-client `impl` classes or other non-`internal` Vert.x impl packages.
- Don't commit, push, tag, or release unless explicitly asked.
- Don't commit secrets, real endpoints, or large binary fixtures. Generate test data at runtime.
- Don't edit generated sources (`*Converter.java`).
````

---

## Verification (after writing the two files)
- `git status` shows only `AGENTS.md` and `CLAUDE.md` as untracked; nothing is committed.
- `cat CLAUDE.md` is exactly `@AGENTS.md`.
- The repo build is unaffected: `mvn -q -DskipTests compile` still passes.
