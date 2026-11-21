# vertx-s3-client revamp — Analysis, Plan, and AGENTS.md proposal

## Scope and non-goals (owner decision, 2026-11-20; applies to every remaining step)
**Goal:** a simple, clean, lean, efficient Vert.x S3 client for basic bucket and object operations,
plus a presigner. **Not** a clone of the AWS SDK.

**In scope:**
- Buckets: create, head, delete, list (ListBuckets, without pagination).
- Objects: `putObject` (Buffer); `getObject` (Buffer, size-capped, small objects only);
  `getObjectStream`; `headObject`; `deleteObject`.
- `putObject` from a stream or file. Multipart is used internally, only when required, and is
  never exposed as a public multipart API.
- ListObjectsV2.
- `S3Presigner`: GET and PUT.
- Optional, to be confirmed by the owner before the step starts: CopyObject, DeleteObjects.

**Non-goals** (never add unless the owner explicitly asks): a public multipart API, ACLs, bucket
policies, lifecycle, versioning, tagging, SSE/encryption options, object lock, presigned POST,
ListBuckets pagination, per-operation timeout settings, a retry framework.

**Rules:**
- Prefer fewer options with good defaults over configurability.
- Every new public method or option must justify itself against this scope. If in doubt, ask the
  owner first.
- Heavy testing is fine; a large public API is not.
- The analysis report (§1–10), the original decision questions and the original AGENTS.md
  proposal below are a historical record. Where they list missing features or options, this
  section decides.

## Context
Total revamp of `object-storage-client` (renaming to `vertx-s3-client`), for Vert.x 5.2.x applications using S3-compatible storage (e.g. DigitalOcean Spaces, MinIO). Hard requirement: idiomatic Vert.x with strict context correctness. This step was **analysis only**. The only action after approval is writing `AGENTS.md` and `CLAUDE.md` at the repo root. Nothing is committed.

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
  - Remove `@VertxGen`. **Keep docgen** (see the build tooling policy correction below).
  - Keep `@DataObject`/`@JsonGen` only for options. Run the codegen and docgen processors through `annotationProcessorPaths` instead of maven-processor-plugin.

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
- Signer → **port + fix**: port the existing logic from git history, fix the audited bugs, and add test vectors. See the recorded decision "SigV4 signer" below. This supersedes the earlier "rewrite".
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
| **1. Rename + skeleton** ⚠️BREAKING | New identity, green build | pom coordinates; delete `objectstorage` tree; new package + `module-info` + docgen-ready `package-info`; Vert.x 5.2.3 pinned; `--release 11`; JUnit 5.14.4 BOM; Testcontainers 2.0.5; codegen **and docgen** processors via `annotationProcessorPaths`; remove web-client and Jackson; `java11` surefire profile; CI: the two JDK jobs only; test TLS certificate + MinIO container; **context-test harness first** (event-loop / worker / virtual-thread verticle fixtures + `assertSameContext` helper). Everything else in build/site/docs stays as is. | Old code can't compile once web-client/Jackson are removed (see Phase 1 steps) |
| **2. Signer + core ops** ⚠️BREAKING | Correct SigV4, idiomatic core | `S3ClientOptions` (endpoint URI, region required, pathStyle, credentials provider, payload strategy) with copy ctor + toJson/fromJson; SigV4 signer **ported** from git history and fixed (sorted/encoded query, RFC3986 path, canonical headers, no options mutation, host:port, session token), unit-tested with AWS SigV4 test-suite vectors; HttpClient transport; context-bound promises; `S3Exception` hierarchy (status, code, requestId, hostId; bodiless + non-XML handling); StAX XML; ops: list/create/head/delete bucket, put(Buffer)/get(Buffer, capped)/getObjectStream/head/delete object, ListObjectsV2, optionally CopyObject and DeleteObjects | Encoding edge cases; Spaces quirks |
| **3. Presigned URLs** (own phase, right after the core client) | GET/PUT presign | Synchronous `S3Presigner` (standalone via `S3Presigner.create(options)` without `Vertx`, also exposed from the client) returning `URI`/`String`; shared SigV4 signer (query auth, `UNSIGNED-PAYLOAD`, cached signing key); separate presigner endpoint; expiry ≤ 604800 s; optional extra signed headers; `response-*` overrides; `X-Amz-Security-Token`; injectable `Clock`; unit tests on AWS published examples; integration via plain HTTP client against Silo (including a non-default port). Presigned POST is out of scope. | Host/port mismatch, clock skew |
| **4. Streaming uploads** | Backpressure-correct uploads | `putObject` from a `ReadStream<Buffer>` and from a file (AsyncFile): pause-before-async, payload hashing per the payload-signing policy, single PUT for small/empty bodies, multipart **internally and only when required** (part size internal, no public option), **AbortMultipartUpload on every failure**, file close on all paths. No public multipart API. (`getObjectStream` moved to Phase 2 step 6.) | Hidden backpressure bugs → test empty, exact-multiple, 1-byte-over, slow-consumer, foreign-context stream |
| **5. Hardening** (lean) | Production-ready | Closing the client aborts in-flight internal multipart uploads; log-redaction audit. The external backend profile stays local (the owner runs it); a CI job only if the owner asks. No retry framework and no per-operation timeout settings (non-goals). | Internal API drift on Vert.x bumps |
| **6. Docs + release** | First release `vertx-s3-client` | README rewrite (Spaces + MinIO config, context guarantees), docgen manual (`src/main/asciidoc` + `examples`) rewritten for the new API, javadoc, CHANGELOG, Central release `0.5.0` (or `1.0.0-alpha1`); see the Phase 6 release checklist below | — |

---

## Recorded decisions (2026-11-20) — supersede the questions below
1. Coordinates: `io.github.emadalblueshi:vertx-s3-client`, package/module `io.github.emadalblueshi.vertx.s3`.
2. Java: main **and test** code compile with `--release 11` (no `testRelease`). The JDK 21 CI job runs the full suite including `@Tag("virtual-threads")`. A `java11` profile auto-activates on JDK 11 and only excludes that tag.
3. `ContextInternal`/`PromiseInternal`/close hooks are allowed only in `impl` packages. Vert.x is pinned, and internal usage is re-verified on every upgrade.
4. **Payload signing (revised 2026-11-20, Option A; originally configurable via `S3PayloadSigning`):** not configurable, the endpoint scheme decides. HTTPS: `UNSIGNED-PAYLOAD` for every request. HTTP: always signed with SHA-256; buffers above a fixed internal 256 KiB threshold are hashed via `executeBlocking`, and upload parts incrementally. Both halves are tested against Silo.
5. Single Maven module.
6. JDK StAX replaces Jackson XML. The parser is hardened and writes are escaped.
7. `HttpClient` directly. `vertx-core` is the only runtime dependency.
8. Keep `@DataObject` plus generated JSON converters for options.

### Recorded decisions (2026-11-20, continued)
9. **SigV4 signer: port, don't rewrite.** Port the existing signing logic from git history (`src/main/java/io/github/emadalblueshi/objectstorage/client/s3/impl/S3SignatureV4.java` and `HashUtil.java` at commit `b7dc1f2`) into the new `impl` package. Then:
   - Fix the four audited bugs: canonical query sorting; key (path) encoding; no mutation of request options/headers; Host signed with the port when it's non-default.
   - Add tests against the AWS SigV4 test vectors.
   - Build presigning on top of the same signer.
   - Also fixed in the port (audit §5/C6, accepted):
     - canonical headers (de-duplicate names, join multi-values, collapse whitespace);
     - a fast hex encoder instead of per-byte `String.format`;
     - no per-call `MessageDigest`/`Mac` provider lookup. `Mac` and `MessageDigest` are **not thread-safe**: use per-thread instances (e.g. a `ThreadLocal` of prototypes) or `clone()` a prototype per use, and never share one instance.
10. **Presigned URLs: an own phase, Phase 3, right after the core client.** Streaming moves to Phase 4.
    - Synchronous API returning `URI`/`String` (pure computation, no `Future`). Usable standalone via `S3Presigner.create(options)` without a `Vertx` instance, and also exposed from the client.
    - One shared SigV4 signer for requests and presigning (query-string auth, `UNSIGNED-PAYLOAD`). The derived signing key is cached per (date, region, service), keyed by credentials too so rotation is safe. The cache holds **only the current entry**, so rotated-out secrets aren't kept in memory.
    - The presigner endpoint is configurable separately from the client endpoint (public host vs internal Kubernetes address). Host is signed with the port when it's non-default.
    - GET and PUT. Expiry is validated (max 604800 s). Optional extra signed headers (e.g. `Content-Type`); by default only `host` is signed. `response-*` overrides, `X-Amz-Security-Token` support, and RFC 3986 key encoding with `/` preserved.
    - Injectable `java.time.Clock`. Unit tests against the AWS SigV4 published examples. Integration tests download and upload via presigned URLs against Silo with a plain HTTP client.
    - Presigned POST (browser form policy) is out of scope for now.
    - *Consequence:* presigning needs credentials synchronously, so the credentials abstraction must expose the current credentials without a `Future`.

### Correction (2026-11-20): build tooling policy
- **Keep docgen. Do not remove it.** The revamp is about the API. Build tooling changes only where a recorded decision requires it: removing web-client and Jackson, the Testcontainers/JUnit versions, `--release 11`, and the processor wiring for codegen and docgen.
- The rest of the existing build, site and doc setup stays as it is: docgen, the asciidoctor site, javadoc, the source and test jars, Central publishing and the gpg profile. This **replaces** the earlier suggestions to drop processor-plugin output, docgen, asciidoctor or the test-jar, to add enforcer/spotless, and to rework CI beyond the two JDK jobs (setup-java v4, caching, permissions, tag pattern, job names). Those suggestions are withdrawn unless you request them.

### Phase 1 — steps (each one is reviewed, verified with `mvn verify`, and committed by the owner)
Steps 1 and 2 land as **one change**. Once web-client and Jackson are removed the old package can't compile, and on Vert.x 5.2.3 the old multipart tests hang anyway (see the test plan below).

1. Build: new coordinates, Vert.x 5.2.3, junit-bom 5.14.4 before `vertx-dependencies`, Testcontainers 2.0.5, `--release 11`, codegen + docgen via `annotationProcessorPaths`, remove web-client and Jackson, `java11` surefire profile.
2. Package skeleton `io.github.emadalblueshi.vertx.s3` (+ `module-info`, + docgen-ready `package-info`). The old package is deleted in the same change.
3. CI: two jobs (JDK 21 full suite, JDK 11 without the `virtual-threads` tag).
4. Test certificate + MinIO Testcontainers setup.
5. Context-test harness (event-loop, worker, virtual-thread verticles) before any client code.

### Test backend and test plan (recorded 2026-11-20)
- **Backend: Silo** (`docker.io/pgsty/silo:RELEASE.2026-09-03T13-18-01Z`), a maintained MinIO fork with the same S3 API, `MINIO_*` env vars and `/minio/health` routes. Upstream MinIO community edition is in maintenance mode and no longer publishes images. The tag is always an exact release (never `latest`) and only changes with the owner's approval.
- **Old multipart path:** it hangs on Vert.x 5.2.3 (`testPutObjectAsStream` and `testPutObjectFileAsStream` time out, then `testGetObjectUpload` fails with NoSuchKey) but passes on 5.0.4. The old tests are removed with the old package. The new streaming tests (Phase 4) must cover the same scenarios: stream upload, file upload, an exact multiple of 5 MiB, an empty stream, and pause/backpressure. Test data is generated at runtime (random bytes of exact sizes: 0, 1 byte, 5 MiB, n × 5 MiB, 5 MiB + 1). No committed binary fixtures.
- **Step 4:** carry the Silo container over into the new test package, updating the tag to `RELEASE.2026-11-12T00-00-00Z` (approved by the owner). Make the integration tests backend-agnostic, with one abstraction for container, endpoint and credentials, so more backends can be added later.
- **Region enforcement (step 4, verified 2026-11-20):** Silo is configured with `MINIO_SITE_REGION=test-west-1`, which makes the server enforce the region. Verified manually with `curl --aws-sigv4` against `RELEASE.2026-11-12T00-00-00Z`:
  - A bucket-level request (ListObjects) signed for `us-east-1` is rejected with 400 `AuthorizationHeaderMalformed` ("the region is wrong; expecting 'test-west-1'").
  - ListBuckets and GetBucketLocation accept any region, by S3 design.
  - `MINIO_REGION` is a legacy alias that behaves the same.
  - `MINIO_REGION_NAME` does **not** set the region; it is accepted silently.
- **Deferred to Phase 2 (needs the signer):** an automated smoke test proving that a request signed for a different region is rejected. Use a bucket-level operation (e.g. ListObjects or HeadBucket on a bucket created with the correct region), not ListBuckets or GetBucketLocation. Assert 400 `AuthorizationHeaderMalformed`, plus success for the same request signed for `test-west-1`.
- **Silo is the only container backend** (owner decision, 2026-11-20). Besides it, step 7 adds one opt-in, generic external backend profile driven by environment variables. Credentials are never committed.

### Phase 1 — additional concrete items
- **Testcontainers 2.0.5** (the latest stable release; the owner already upgraded it in `14a9fd5`). The existing suite passes against Docker 29 with plain `mvn test`. Java 8 bytecode, so it works on the JDK 11 job.
- **JUnit:** import `junit-bom` **5.14.4** in `dependencyManagement` *before* `vertx-dependencies`. The Vert.x 5.2.3 BOM manages JUnit Jupiter 6.0.0, which is Java 17 bytecode and would break the JDK 11 job. vertx-junit5 5.2.3 (Java 11 bytecode) is built against JUnit 5.14.0.
- **Pin Vert.x** to an exact version (5.2.3). The only runtime dependency is `vertx-core`. Remove `vertx-web-client` and Jackson (databind, dataformat-xml, jsr310). **Keep docgen**: `vertx-docgen-processor`/`-api` at the BOM-managed 0.9.9.
- **Compiler:** `maven.compiler.release=11` for main and test. Codegen and docgen processors run via `annotationProcessorPaths`, replacing maven-processor-plugin. The processors are listed explicitly, because the docgen processor jar has no `META-INF/services` entry.
- **Surefire:** a `java11` profile (`<activation><jdk>11</jdk></activation>`) sets `excludedGroups=virtual-threads`, and nothing else.
- **CI:** two jobs. JDK 21 runs `mvn -B verify` (full suite). JDK 11 runs `mvn -B verify` (the profile excludes the VT tag). No `-Dapi.version` anywhere. The Deploy/Release jobs and other CI settings stay as they are.
- **TLS test fixture:** a committed self-signed certificate and key under `src/test/resources`, named as test-only (e.g. `test-only-minio-selfsigned.{crt,key}`), with a long expiry (~100 years) and SANs `localhost` and `127.0.0.1`. It's mounted into the Silo container for the HTTPS half of the payload-signing matrix.
- The context-test harness (event-loop, worker and virtual-thread verticle fixtures plus an `assertSameContext` helper) is built first, with the VT fixture tagged `virtual-threads`.

### Phase 1 — status
Complete (2026-11-20): rename + skeleton, CI, Silo test backend with TLS, context-test harness.

### Phase 2 — steps (recorded 2026-11-20; each step is reviewed, verified with `mvn verify`, and committed by the owner)
Order rationale: options come before the transport, which consumes them (endpoint, addressing style, TLS, timeouts, payload policy), so no throw-away internal config types are needed. XML is split out of the transport because it is pure code with unit tests only. Every public method gets context-harness coverage for all four caller kinds. Each operation group (steps 4–9) adds docgen `Examples` snippets and an `index.adoc` section.

| # | Step | Contents | Tests |
|---|---|---|---|
| 1 | SigV4 signer (`impl`, package-private) | Port `S3SignatureV4`/`HashUtil` from `b7dc1f2` with all audited fixes (decision 9); injectable `Clock`; the payload hash is an input. | Vendored AWS SigV4 suite (botocore copy, Apache-2.0, with LICENSE and NOTICE); S3 unit tests; Silo: the deferred wrong-region test, special-character keys, unsorted multi-parameter query. Plus `PackagePolicyTest` for the package layout rules. |
| 2 | Options and credentials (public, `@DataObject` + codegen) | `S3ClientOptions` (endpoint URI, required region, path-style vs virtual-hosted, credentials, payload-signing policy + `executeBlocking` threshold, timeouts, `HttpClientOptions` passthrough); `S3Credentials` (access key, secret, session token); a synchronous credentials-provider interface; copy constructors, `toJson`/`fromJson`. | Defaults, copy semantics, JSON round trip, secret redaction, validation. |
| 3 | XML (`impl`) | Hardened StAX reader (DTDs and external entities off), escaping writer, the S3 `Error` document parser. Operation model parsers land with their operations. | Escaping, XXE and entity-expansion rejection, error variants (AWS, MinIO, Spaces), unknown elements ignored. |
| 4 | Transport, errors, client lifecycle | `S3Client.create`/`close()` on `HttpClientAgent`; the single impl context helper (the only `ContextInternal` user): context-bound promises and the close hook; request building; payload-signing policy; `Content-Length` always set; `S3Exception` hierarchy (bodiless and non-XML errors, 301 region redirects); ListBuckets as the first operation; the **rule-3 guard test** in `PackagePolicyTest`. | Error mapping against a local Vert.x `HttpServer` stub; ListBuckets against Silo; harness for `close()` and ListBuckets. |
| 5 | Bucket ops | createBucket (with `LocationConstraint`), headBucket, deleteBucket. Bucket-name validation, including dotted names with virtual-hosted style over TLS. `User-Agent: vertx-s3-client/<version>` on every request (version from build metadata). | NoSuchBucket, BucketAlreadyOwnedByYou, BucketNotEmpty; name validation; User-Agent; harness. |
| 6 | Object ops | putObject(Buffer) (`Content-Length` always set; content type, user metadata), getObject → Buffer (small objects only, size-capped), **getObjectStream** (issue #36; Range supported), headObject, deleteObject. See "Step 6 requirements" below. The commit says "Closes #36". | Payload signing over HTTP (signed, inline and via `executeBlocking`) and HTTPS (unsigned); the cap (with and without `Content-Length`); getObjectStream backpressure, ranges, errors before the body; special-character keys; runtime-generated data; harness, including `assertReadStreamOnCallerContext`. |
| 7 | External backend profile (opt-in) | A generic `ExternalBackend` behind `S3TestBackend`, enabled only when `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY` and `S3_SECRET_KEY` are all set (otherwise skipped, never failed). No provider names, nothing secret in the repo, no CI job: the owner runs it locally. The bucket and object suites become abstract bases with Silo, Silo-TLS and External subclasses. Every created resource has a unique `vertx-s3-it-` name and is deleted in cleanup even on failure. One bucket create/head/delete test; all object tests share one bucket. | Against the external backend: CreateBucket `LocationConstraint`; `UNSIGNED-PAYLOAD` on bodiless and buffered requests (https endpoint); error mapping (`NoSuchKey`, `NoSuchBucket`, HEAD 404, auth errors); put/get/head/delete round trips; `getObjectStream` with backpressure, Range and cancel; special-character keys including empty/`.`/`..` segments and a leading `/`; the getObject cap. |
| 8 | ListObjectsV2 (issue #32) | Prefix, delimiter, common prefixes, start-after, continuation tokens (the caller pages; no paging helper). `encoding-type=url` with key decoding. | Multi-page listings; special-character keys; harness. |
| 9 | CopyObject + DeleteObjects (optional; the owner confirms before the step starts) | Copy-source encoding; CopyObject's 200-with-`<Error>`-body case; DeleteObjects with the required `Content-MD5`, quiet mode, per-key errors. | Same pattern; per-key failure reporting. |

### Phase 2 — answers recorded (2026-11-20)
1. `toJson` leaves the secret out; `fromJson` still accepts it (JSON is therefore not a full round trip for credentials).
2. `S3Client.create()` throws `IllegalArgumentException` on invalid options (a synchronous factory; configuration errors fail fast).
3. A rule-3 guard test in `PackagePolicyTest` (scan `src/main` for context-less `Promise.promise()`, `Future.succeededFuture`/`failedFuture` and `Future.all`/`any`/`join`), with an explicit allowlist for the single impl helper that uses `ContextInternal`. It lands in step 4 with that helper. AGENTS.md: "review and the guard test enforce rule 3".
4. Docgen `Examples` snippets and an `index.adoc` section with each operation group (steps 4–9).
5. Name clash with AWS SDK v2 (`S3Client`, `S3Presigner`, `S3Exception`): keep our names. The user docs (Phase 6 README and the docgen manual) note that a file importing both libraries uses fully qualified names.
6. The AWS S3 documentation examples are skipped: the page isn't fetchable by automated clients, and Silo's server-side signature verification is the S3-specific oracle.

### Phase 2 — step 2 decisions and deviations (recorded 2026-11-20)
- **Composition, not inheritance:** `S3ClientOptions` holds `HttpClientOptions` and `PoolOptions` (transport only); the endpoint URI is the single source of scheme, host and port. Vert.x 5.2.3's newer `HttpClientConfig` was not used: it is `@Unstable` and has no JSON support.
- **`pathStyleAccess` defaults to `true`.** This differs from the AWS SDK default (virtual-hosted-style) and is documented as such on `S3ClientOptions`.
- **Deviation from the step 2 plan row: no S3-level timeout options.** Connect and idle timeouts come from the composed `HttpClientOptions`. Per-operation timeout settings are a non-goal (see the scope section).
- **Validation moves to step 4.** `S3Client.create()` validates everything and throws `IllegalArgumentException`: endpoint URI (http/https, host, optional port, no path/query/user info), required region, exactly one credential source, no default host/port/`ssl` set in `httpClientOptions`, and `UNSIGNED` payload signing over `http://`. Step 2 has only setter-level checks.
- **AGENTS.md change scheduled for step 4:** the payload-signing policy line becomes "`UNSIGNED-PAYLOAD` over plain HTTP is rejected by `create()`" (it currently says "failed Future").
- **Secrets:** `toJson()` omits and `toString()` masks the secret key and session token. `S3ClientOptions.toJson()` also strips the `password`/`aliasPassword` entries that Vert.x writes in clear in `HttpClientOptions` JSON (key and trust stores, proxy auth).
- **Generated converters:** codegen emits `public class XConverter` with package-private methods even with `publicConverter = false`, as in Vert.x core. `PackagePolicyTest` allows exactly that shape. The javadoc config stays unchanged, so the javadoc jar includes their (empty) pages.

### Phase 2 — findings
- **Backend difference: key segments.** Silo (like MinIO) rejects keys with empty, `.` or `..` segments (`a//b`, `a/./b`, `a/../b`) with `XMinioInvalidObjectName` / `XMinioInvalidResourceName`. They are valid on AWS. That the signer doesn't normalise paths is therefore unit-tested only (`S3SignatureV4Test`), not checked against Silo. Re-check with an external backend (step 7: `dotSegmentKeys` in the external object suite).
- **A client can't be used after its `Vertx` is closed** (step 4). The close hook closes it, but Vert.x can no longer deliver Future callbacks on a closed instance (`RejectedExecutionException: event executor terminated`), and that applies to every Vert.x client. Documented on `S3Client`; tested via the package-private `S3ClientImpl.isClosed()`.
- **Virtual-hosted-style addressing is unit-tested only** (step 4). It needs a DNS endpoint (`bucket.host`), and `create()` rejects IP/localhost endpoints for it. Verify end to end with the step 7 external profile and `S3_PATH_STYLE=false`.
- **`encoding-type=url` encodes keys form-style.** A space comes back as `+` (verified against Silo). In step 8, ListObjectsV2 key decoding must treat `+` as a space.

### Phase 2 — step 4 answers (recorded 2026-11-20)
1. **ListBuckets has no pagination.** Superseded by the scope section: pagination is a non-goal.
2. **Buffered-response cap.** Superseded: the `getObject` size cap moves to step 6 (see "Step 6 requirements").
3. **Bucket-name validation in step 5**, including dotted names with virtual-hosted style over TLS: `a.b.host` doesn't match a `*.host` wildcard certificate.
4. **`User-Agent: vertx-s3-client/<version>` in step 5**, with the version read from build metadata, not hard-coded.

### Phase 2 — step 5 findings and decisions (2026-11-20)
- **CreateBucket location constraint:** the client's region is sent as `LocationConstraint`, except for `us-east-1`, where AWS rejects it. No public option. Silo, which enforces `test-west-1`, accepts it. **Verify against Spaces in step 7.**
- **HeadBucket failures have no S3 error code:** HEAD responses have no body. A missing bucket is an `S3ServiceException` with status 404 and a `null` error code; no code is synthesized. Documented, with an "exists" example in the manual.
- **Bucket-name validation** follows the general S3 rules. AWS-only reserved prefixes and suffixes are left to the server. Legacy names with uppercase letters or underscores (pre-2018 us-east-1 buckets) are rejected. Dotted names are rejected only with virtual-hosted-style access over HTTPS, with a message pointing to `pathStyleAccess = true`; there is no silent fallback to path-style.
- **User-Agent version source:** a Maven-filtered `impl/version.properties` (one filtered resource directory in the pom). The jar manifest was rejected because fat-jar shading replaces or merges it, which would report the application's version. The module descriptor was rejected because it's absent on the class path. The header isn't signed, as in the AWS SDKs, because proxies may rewrite it.

### Phase 2 — step 6 requirements (recorded 2026-11-20)
- **`getObjectStream` (issue #36).** Large objects (e.g. 1 GB) are never buffered in memory.
  - The Future completes when the response headers arrive. An error response fails it with an `S3ServiceException` before any body is streamed.
  - On success it gives a `ReadStream<Buffer>` plus metadata: content length, ETag, content type, last modified, user metadata.
  - The response is paused before the Future completes, so no data is lost before the caller attaches its handlers.
  - Range is supported.
  - Stream handlers run on the caller's context (rule 4). The HTTP request must therefore be issued on the captured context: `HttpClient.request()` called from a non-Vert.x thread would otherwise use a context of its own.
  - Tests: `assertReadStreamOnCallerContext` for all caller kinds, including backpressure, plus ranges, an empty object and an error before the body.
  - The step 6 commit says "Closes #36".
- **Buffered `getObject` cap.**
  - A configurable maximum size with a small default (a few MiB; the value is proposed in step 6).
  - If `Content-Length` exceeds it, fail before reading the body with an `S3ClientException` that points to `getObjectStream`.
  - Without `Content-Length`, abort the response as soon as the limit is crossed, with the same exception.
  - `getObject` is documented as "small objects only".
- **Other buffered bodies (owner answer).** XML results and error bodies have a fixed internal limit, with no option, sized for the largest legitimate response (a full ListObjectsV2 page), e.g. 4 MiB. Error bodies keep only a truncated excerpt.
- **Abandoned streams (owner answer).** An abandoned `getObjectStream` stream must not leak a pooled connection. The returned stream type gets one small cancel method that resets the request and releases the connection. The docs say the stream must be consumed or cancelled. The exact shape is proposed in the step 6 diff.

### Phase 2 — step 7: external backend profile (2026-11-20)
- **PENDING (owner has no Spaces account yet, 2026-11-20):** Run the external profile against real Spaces before the first release, in both addressing styles:
  ```
  export S3_ENDPOINT=https://<region>.digitaloceanspaces.com
  export S3_REGION=<region>
  export S3_ACCESS_KEY=<key>
  read -rs S3_SECRET_KEY && export S3_SECRET_KEY
  mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false
  S3_PATH_STYLE=false mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false
  unset S3_ENDPOINT S3_REGION S3_ACCESS_KEY S3_SECRET_KEY
  ```
  Unverified until then: LocationConstraint, UNSIGNED-PAYLOAD on Spaces, virtual-hosted addressing, dot-segment and leading-slash key behavior.
- **External backend target:** the external profile will run against DigitalOcean Spaces, which is built on Ceph RGW. That covers Ceph-specific behaviour, so no Ceph or SeaweedFS container is planned.
- **Running it:** set `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY` and `S3_SECRET_KEY`, then `mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false`. Optional, test-only `S3_PATH_STYLE=false` runs the same suites with virtual-hosted-style addressing (owner decision; the default stays path-style). No Docker is needed: the external suites don't start containers. A full `mvn verify` with the variables set runs them too.
- **What the external suites expect:** AWS semantics. Keys with empty, `.` or `..` segments round-trip, and `/x` and `x` are distinct objects. A backend that behaves otherwise fails exactly those tests, naming the server's error. That is how differences are reported.
- **New finding: Silo strips a leading `/` from keys** (verified): `/x` and `x` are the same object. On AWS they are distinct. The Silo subclasses expect this (`stripsLeadingSlash`).
- **Plumbing verified against a stand-in:** the external suites were run against a separately started Silo container (pinned tag) through the four variables. Everything passed except the 8 AWS-semantics key tests, which reported Silo's behaviour. No `vertx-s3-it-` bucket was left, although tests failed. A partial configuration reports all 19 external tests as skipped. The secret appeared in no log or report.
- **Virtual-hosted-style addressing** is covered by running the profile with `S3_PATH_STYLE=false`. It can't be checked against the local stand-in, because `create()` rejects IP and localhost endpoints for virtual-hosted style; only the configuration parsing is unit-tested (`ExternalBackendTest`).

### Phase 2 — step 6 decisions and findings (2026-11-20)
- **Public API added:**
  - `putObject(bucket, key, Buffer[, S3PutObjectOptions])` returns the ETag (`Future<String>`).
  - `getObject(bucket, key)` returns `Future<Buffer>`, capped.
  - `getObjectStream(bucket, key[, range])` returns `Future<S3ObjectStream>`.
  - `headObject` returns `Future<S3ObjectMetadata>`.
  - `deleteObject` returns `Future<Void>`.
  - New types: `S3ObjectStream` (a `ReadStream<Buffer>` with `metadata()` and `cancel()`), `S3ObjectMetadata` and `S3PutObjectOptions` (content type and user metadata only).
  - New option: `S3ClientOptions.maxBufferedObjectSize`, default 4 MiB.
- **Range** is a single HTTP `bytes=` value (`bytes=0-99`, `bytes=100-`, `bytes=-100`), validated before I/O. No range type.
- **ETags** are returned exactly as sent, including their quotes. `getEtag()` is the generated name.
- **Non-ASCII metadata values are rejected, not RFC 2047-encoded** (owner decision). The javadoc and manual tell callers to URL-encode such values themselves, or to keep them in the key or their own database.
- **No `Content-Range` exposure** on ranged streams for now (owner decision); `headObject` gives the total size.
- **`cancel()`** uses Vert.x 5's `HttpClientRequest.cancel()` (it closes an HTTP/1.1 connection that is in flight), then resumes the response to drain anything already received. The stream's exception handler receives `S3ClientException("The object stream was cancelled")` unless the stream had already ended, so `pipeTo` fails instead of hanging.
- **`collect()` resumes the paused stream**, as `pipeTo` does. Vert.x's default `collect` doesn't resume, so it would hang on a paused stream.
- **Finding:** Vert.x 5.2.3 `HttpClientResponseImpl` drops body chunks while no handler is set, and throws when a handler is set after the end. `ObjectStreamImpl` therefore attaches its own handlers at construction, on the paused response.
- **Finding:** a HEAD response's `Content-Length` (e.g. 10 GiB) must not count against buffering limits. It is pinned by a stub test.
- **Not observable (equivalent mutant):** issuing the request through `Caller.onContext`. `send()` already runs on the caller's context because it is composed on a Future bound to it. `onContext` is kept as an explicit guard.

### Payload signing simplification (owner decision 2026-11-20, Option A)
Done in a separate **BREAKING** commit between steps 5 and 6, which revised decision 4:
- No public setting. `S3PayloadSigning` and `maxInlinePayloadHashSize` are removed.
- HTTPS: `UNSIGNED-PAYLOAD` for every request.
- HTTP: the payload is always signed with SHA-256. Buffers above a fixed internal threshold (256 KiB) are hashed via `executeBlocking`; upload parts are hashed as their chunks arrive.
- The same commit updates AGENTS.md's payload-signing section and decision 4.

### GitHub issues (checked 2026-11-20)
- #31 "Supports list buckets": implemented in step 4 (`8582fe1`). Closed by the step 8 commit message ("Closes #31").
- #32 "Supports list objects": implemented by step 8 (`listObjects`, ListObjectsV2). Closed by the step 8 commit message ("Closes #32").
- #36 "Supports get object as stream": implemented by step 6 (`577f135`, `getObjectStream`), whose commit closed it.

### Phase 2 — step 8: ListObjectsV2 (2026-11-20)
- **API:** one method, `listObjects(bucket, S3ListObjectsOptions)`, which returns one page as `S3ListObjectsResult`. `null` options means none.
  - **Options** (`S3ListObjectsOptions`): `prefix`, `delimiter`, `maxKeys` (1–1000, `null` for the server default), `continuationToken` and `startAfter`. Unset or empty values are not sent.
  - **Result:** `objects` (`S3ListedObject`: key, size, ETag as sent, last modified), `commonPrefixes`, `truncated` and `nextContinuationToken`.
  - No owner, storage class or paging helper: the caller pages with the continuation token.
  - `S3ListedObject` is named so it doesn't clash with AWS SDK v1's `S3ObjectSummary`.
- **Encoding:** every request sends `encoding-type=url`. Keys and prefixes are decoded only when the response says `<EncodingType>url</EncodingType>`: form-style (`+` is a space, `%XX` is UTF-8), via `URLDecoder`. A server that ignores the parameter therefore can't have keys mangled.
- **Finding:** without `encoding-type=url`, Silo returns keys raw, and the special-character tests (Arabic, space, `+`, `%`, `?`, `#`, `&`, quotes) still pass. The encoding is what protects keys containing characters XML 1.0 can't carry (e.g. control characters). Sending it is pinned by the query tests.
- **Truncated without a token fails** (owner decision): `truncated=true` with no `NextContinuationToken` fails with `S3ClientException("ListObjectsV2 response is truncated but has no continuation token")`. Returned as is, a standard `while (truncated)` paging loop would restart from page 1 forever. A stub test pins it.
- **Response limit:** the fixed 4 MiB XML limit (`Transport.execute`). An undecodable key fails with `S3ClientException("Invalid ListObjectsV2 response: …")`.
- **Tests:**
  - Shared suite (Silo HTTP/HTTPS and external):
    - paging across 3 pages (7 keys, `maxKeys` 3);
    - `startAfter`;
    - prefix and delimiter "folders";
    - special-character keys and a special-character folder prefix (including Arabic);
    - listed objects agree with `headObject`;
    - `NoSuchBucket`.
  - The bucket suite's single lifecycle bucket also checks that a new bucket lists empty, so no extra bucket is created.
  - Context harness: success and failure for all four caller kinds.
  - Stub: the exact sorted, encoded query.
  - The external suites were also run against a pinned-tag Silo stand-in: all listing tests passed.

### Deprecation fix (2026-11-20, after step 8)
- **`ContextInternal.addCloseHook(Closeable)` is `@Deprecated` in Vert.x 5.2.3** ("instead use `registerResource(CloseableResource)`"). `ContextSupport.registerResource` now does what `HttpClientBuilderInternal` does for Vert.x's own `HttpClient`: `VertxInternal.registerResource(CloseableResource.of(resource))`. The owner is the current context's close future inside a verticle, otherwise the `Vertx` instance's, the same resolution as before.
- **Explicit `close()` unregisters.** It goes through the returned `ResourceHook`, whose `shutdown()` removes it from the owner and is memoized, so a closed client is no longer referenced by its context until undeploy. A new test checks the owner's `CloseFuture` directly (`remove` reports whether the registration was still there). The existing close-hook tests pass unchanged, and they wouldn't catch a missing unregistration (verified by mutation).
- **`create()` on a closed owner** (the context of an already undeployed verticle) now throws `IllegalStateException`, because `registerResource` returns `null` and nothing could close the client. Vert.x's own `HttpClient` is silently left unregistered in that case. On a closed `Vertx`, creation already fails earlier, in Vert.x's HTTP client constructor (`RejectedExecutionException`).
- **No deprecated APIs in `src/main`, enforced by `NoDeprecatedApiTest`** (no pom change). The test compiles `src/main` plus the generated converters with the JDK's `javac` in a child process (`--release 11 -proc:none -Xlint:deprecation`). It fails on every `[deprecation]`/`[removal]` warning, naming file and line. Verified: it fails on the old `addCloseHook` call and passes after the fix, on JDK 25 and 21; JDK 11 is covered by CI.
- **Alternatives evaluated:**
  - `-Werror` on the main compile: impossible. The docgen processor always warns "Supported source version 'RELEASE_8' … less than -source '11'", and no `-Xlint` switch silences it. With `-Werror`, javac even stops after annotation processing, before reporting the deprecation.
  - A second `maven-compiler-plugin` execution (no processors, its own output and generated-sources directories, `-Xlint:deprecation -Xlint:-module`, `failOnWarning`): works. But the `compile` goal unconditionally re-points the project artifact at its output directory, which logs "Overwriting artifact's file" on every build. Sharing the default generated-sources directory also cleared default-compile's converters.
  - The in-process compiler API (`javax.tools`) in a test: the tests run inside the library's module, which doesn't read `java.compiler`; surefire would need extra flags.

### Phase 6 — release checklist
- [ ] Run the external profile against real Spaces before the first release, in both addressing styles:
  ```
  export S3_ENDPOINT=https://<region>.digitaloceanspaces.com
  export S3_REGION=<region>
  export S3_ACCESS_KEY=<key>
  read -rs S3_SECRET_KEY && export S3_SECRET_KEY
  mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false
  S3_PATH_STYLE=false mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false
  unset S3_ENDPOINT S3_REGION S3_ACCESS_KEY S3_SECRET_KEY
  ```
  Unverified until then: LocationConstraint, UNSIGNED-PAYLOAD on Spaces, virtual-hosted addressing, dot-segment and leading-slash key behavior.

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
(e.g. DigitalOcean Spaces; MinIO for local dev/tests).

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
