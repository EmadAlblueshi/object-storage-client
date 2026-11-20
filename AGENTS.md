# AGENTS.md — vertx-s3-client

## Purpose
Asynchronous, idiomatic Vert.x 5 client for Amazon S3 and S3-compatible storage
(e.g. DigitalOcean Spaces; MinIO-compatible servers for local dev and tests).

**Rename in progress:** formerly `object-storage-client`
(`io.github.emadalblueshi.objectstorage.client`). The old API is being replaced, not
migrated; backward compatibility is NOT a goal. Do not add code to the old package.

**Roadmap:** [`docs/REVAMP_PLAN.md`](docs/REVAMP_PLAN.md) is the source of truth for the
revamp roadmap, phases and recorded decisions. Follow it; when it and this file disagree, ask.

## Tech stack
- Maven, single module.
- **Java:** main and test code both compile with `--release 11` (the Vert.x 5 baseline;
  `maven.compiler.release` 11, no `testRelease`). The full suite runs on JDK 21.
- **Vert.x 5.2.x, pinned to an exact version** in the pom (currently 5.2.3). Transport is
  `io.vertx.core.http.HttpClient`. No WebClient.
- **`vertx-core` is the only runtime dependency.** XML uses JDK StAX (`java.xml`); no Jackson
  XML, no other libraries.
- `vertx-codegen` (provided scope): `@DataObject` + generated JSON converters for options classes.
- `vertx-docgen` (provided scope): generates the Asciidoc manual from `src/main/asciidoc/*.adoc`
  and the `examples` package. Both processors run via `annotationProcessorPaths`.
- Tests: JUnit Jupiter **5.14.4** (`junit-bom` imported BEFORE `vertx-dependencies`; don't
  take the JUnit 6 that the Vert.x BOM manages, because it needs Java 17), `vertx-junit5`,
  Testcontainers **2.0.5** (works with Docker 29).
- Test backend: **Silo** (`docker.io/pgsty/silo`), a maintained MinIO fork with the same S3 API,
  `MINIO_*` env vars and `/minio/health` routes. Upstream MinIO community edition no longer
  publishes images.

## Coordinates & layout
- Maven: `io.github.emadalblueshi:vertx-s3-client`
- Module: `io.github.emadalblueshi.vertx.s3`
- `examples` — docgen snippets referenced from `src/main/asciidoc`. Compiled, but excluded
  from the jar, sources and javadoc.

## Package layout (idiomatic Vert.x; enforced by `PackagePolicyTest`)
- `io.github.emadalblueshi.vertx.s3` — **public API only**: interfaces, `@DataObject`
  options/models, the `S3Exception` hierarchy, and factories (e.g. `S3Client.create`, which
  delegates to impl).
- `io.github.emadalblueshi.vertx.s3.impl` — **all implementation**: client impl, signer,
  transport, XML, context helper. Sub-packages under `impl` only if clearly needed.
- `module-info` exports only the API package (unqualified, nothing opened). `impl` is never exported.
- Impl is hidden on the class path too: every impl class that can be package-private is. No
  public API signature (return, parameter, throws, supertype, type bound, generics) mentions an
  impl type. Impl classes that must be public go on the explicit allowlist in
  `PackagePolicyTest`, each with a reason.
- Public type names are `S3`-prefixed and must not clash with JDK, Vert.x or common library
  names. No generic names like `Credentials`, `Options` or `Bucket`. Deliberate exception:
  `S3Client`, `S3Presigner` and `S3Exception` keep the same names as AWS SDK v2 types (owner
  decision). The user docs tell code importing both libraries to use fully qualified names.
- Generated JSON converters are the one exception in the API package. Vert.x codegen generates
  an `XConverter` next to each data object `X`: a public class with only package-private
  methods, even with `publicConverter = false`, as in Vert.x core. `PackagePolicyTest` allows
  exactly that shape.
- Options classes compose Vert.x options, never extend them. `S3ClientOptions` holds
  `HttpClientOptions` and `PoolOptions` (transport only); the endpoint URI is the single source of
  scheme, host and port. Presigner-specific options (public endpoint) belong to Phase 3, not
  `S3ClientOptions`.

## Build tooling policy
- The revamp is about the API. Change build tooling **only where a recorded decision requires
  it**: removing web-client and Jackson, the Testcontainers/JUnit versions, `--release 11`, and the
  processor wiring for codegen and docgen.
- Leave the rest of the existing build, site and doc setup as it is: docgen, the asciidoctor
  site, javadoc, the source and test jars, Central publishing and the gpg profile.

## Commands
- Compile: `mvn -q -DskipTests compile`
- Full suite (JDK 21, needs Docker): `mvn verify`
- JDK 11 check: `mvn verify` on JDK 11. The `java11` profile auto-activates and only excludes
  the `virtual-threads` tag; it changes nothing else.
- Single test: `mvn test -Dtest=ClassName#method`
- Site/docs: `mvn -DskipTests package site` (docgen output: `target/asciidoc/java`)

## Java 11 rules (`src/main` and `src/test`)
- No records, sealed types, switch expressions, text blocks, or pattern matching for `instanceof`.
- No post-11 JDK APIs (`HexFormat`, `Stream.toList()`, `String.formatted`, `Thread.ofVirtual`, …).
  `--release 11` enforces the API part.
- Virtual-thread support comes only through Vert.x (`ThreadingModel.VIRTUAL_THREAD` on the
  caller's side). The library never references virtual-thread JDK APIs.

## Vert.x rules (NON-NEGOTIABLE)
1. **Future-only public API** (Vert.x 5 style). No callback overloads, no
   `CompletableFuture`, no blocking methods in the public API. The only exception is
   presigning: it is pure computation with no I/O, so it is synchronous and returns
   `URI`/`String` directly (see "Presigned URLs").
2. **Context capture:** every public async method captures the caller's context at call
   time and completes its Future ON THAT CONTEXT, whether the caller is an event loop, a
   worker, or a virtual-thread verticle. Never use an unbound `Promise.promise()` for a
   result that crosses an async boundary.
3. **Context-bound Futures only:** every Future returned by the public API comes from a
   promise bound to the caller's context, captured at call time: `ContextInternal.promise()`
   on the captured context, or a composition (`compose`/`map`/`transform`/`eventually`/
   `recover`) of a Future that is itself bound to that context. Immediate results and
   validation failures use the captured context's `succeededFuture(...)`/`failedFuture(...)`.
   Never return a Future built on a context-less `Promise.promise()`, `Future.succeededFuture`
   or `Future.failedFuture`. Combinators `Future.all`/`any`/`join` are context-less too
   (verified in 5.2.3): complete a captured-context promise from them instead. That way,
   which context a listener runs on never depends on response timing, i.e. on whether the
   Future completed before or after the caller attached its listener.
4. **Streams:** `ReadStream`/`WriteStream` handlers (`handler`, `endHandler`,
   `exceptionHandler`, `drainHandler`) run on the owning context, with real backpressure
   (`pause`/`resume`/`fetch`, `pipeTo`). Pause any user-supplied stream BEFORE the first
   async step. Never emit while paused.
5. **Never block the event loop:** no blocking file I/O (use `FileSystem`/`AsyncFile`),
   no `*Blocking` calls, no heavy inline CPU (see the payload-signing policy).
6. **No own threads, executors, or static `Vertx`.** The client is bound to the `Vertx`
   passed in. `close()` returns `Future<Void>`. A client created inside a verticle is
   closed when that context closes (close hook).
7. **Prefer Vert.x primitives:** `HttpClient`, `Buffer`, `Future` composition
   (`compose`, `transform`, `eventually`, `recover`), Vert.x timers for retries/timeouts,
   JsonObject-backed `@DataObject` options classes with a copy constructor plus `toJson`/`fromJson`.
8. Public async methods never throw; validation failures return a failed Future.

## Vert.x internal API policy
- `io.vertx.core.internal` (`ContextInternal`, `PromiseInternal`, close hooks) is allowed
  **only inside `impl` packages**, never in the public API's signatures.
- Use it for context-bound promises (`ContextInternal.promise()`) and
  `addCloseHook`/`removeCloseHook`. Any other internal API needs explicit approval.
- **On every Vert.x version bump, re-verify every internal API usage** (compile, plus the
  context test suite on all three threading models) before merging.

## Payload-signing policy
- Configurable on the client options. The defaults are:
  - Streaming/multipart over HTTPS: `UNSIGNED-PAYLOAD`.
  - Small buffered requests: signed SHA-256. Above a configurable size threshold, the
    hash is computed via `executeBlocking`, never inline on the event loop.
  - Over plain HTTP: payloads are always signed. `UNSIGNED-PAYLOAD` is rejected (failed Future).
- Never send AWS-only trailing checksums (`aws-chunked`, `x-amz-checksum-*` trailers) by
  default.

## S3 conventions
- Path-style and virtual-hosted-style are both supported. The endpoint is a URI, the region is required
  (no magic defaults). A separate public endpoint is allowed for presigned URLs.
- SigV4: sorted, RFC 3986-encoded canonical query; S3 single-encoded path; Host includes
  a non-default port; request options are immutable per call and never mutated by the client.
- **One shared SigV4 signer** (in `impl`) serves both requests and presigning. It is **ported**
  from the original `S3SignatureV4`/`HashUtil` (git history, `b7dc1f2`) and fixed, NOT rewritten
  from scratch. The derived signing key is cached per (date, region, service, credentials), and
  the cache holds only the current entry, so rotated-out secrets aren't kept in memory.
- `Mac`/`MessageDigest` are not thread-safe: use per-thread instances or `clone()` a prototype per
  use, and never share one instance.
- Multipart: abort on every failure path; close every opened `AsyncFile`; an empty or small body
  uses a single PutObject.
- Errors map to the library's `S3Exception` hierarchy (HTTP status, S3 code, requestId);
  handle bodiless (HEAD) and non-XML error responses.

## Presigned URLs
- A synchronous API returning `URI`/`String`, with no `Future`. It's usable standalone via
  `S3Presigner.create(options)` without a `Vertx` instance, and is also exposed from the client.
- Query-string auth with `UNSIGNED-PAYLOAD`, through the shared signer.
- The presigner endpoint is configurable separately from the client endpoint (public host vs
  internal Kubernetes address). Host is signed with the port when it's non-default.
- GET and PUT. Expiry is validated (max 604800 s). Only `host` is signed by default; optional
  extra signed headers (e.g. `Content-Type`) are allowed. `response-*` overrides,
  `X-Amz-Security-Token` support, and RFC 3986 key encoding with `/` preserved.
- Time comes from an injectable `java.time.Clock` (default: the system UTC clock).
- Presigned POST (browser form policy) is out of scope.

## XML
- JDK StAX only, always via `XMLInputFactory.newDefaultFactory()`: never the StAX provider on
  the application's class path (e.g. Woodstox), so the hardening behaves the same everywhere.
  One factory per parse (the JDK factory isn't thread-safe to share).
- Parsers disable DTDs and external entities (`SUPPORT_DTD=false`,
  `IS_SUPPORTING_EXTERNAL_ENTITIES=false`, empty `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA`)
  and reject any DTD or entity-reference event outright. Depth and element-count limits apply.
  All parsing goes through `impl.S3Xml`.
- Request bodies are written only with `impl.XmlWriter`, which escapes all text and attribute
  values (keys, ETags, tags) and rejects characters XML 1.0 can't represent. Never build XML by
  string concatenation.

## Security
- Never log credentials, `Authorization`, `x-amz-security-token`, or presigned URLs.
- Options `toJson()`/`toString()` never expose secrets: the secret key and session token are
  omitted or masked. `S3ClientOptions.toJson()` also strips the `password`/`aliasPassword`
  entries that Vert.x writes in clear inside the nested `HttpClientOptions` JSON (key and trust
  stores, proxy auth).
- No default credentials; fail fast when they are missing.

## Testing rules
- vertx-junit5. Integration tests run against Silo via Testcontainers. The image is pinned to an
  exact release tag (never `latest`); don't change the tag without asking.
- Integration tests are backend-agnostic: one abstraction supplies the container, endpoint and
  credentials, so more backends can be added (planned: SeaweedFS, and an optional DigitalOcean
  Spaces profile that runs only when credentials come from environment variables or CI secrets).
  Backend credentials are never committed.
- Every public async operation has a context test: from an **event-loop verticle**, a
  **worker verticle**, and a **virtual-thread verticle**, assert that
  `Vertx.currentContext()` inside every callback / Future completion is the same instance as
  the context captured before the call.
- The harness complements the context-bound-Futures rule (Vert.x rule 3) but can't replace it.
  It can't detect timing-dependent violations: if a Future is already complete when the harness
  attaches its listener, the listener runs synchronously on the caller's thread and the check
  passes, even if the implementation used a context-less promise. Review and the rule-3 guard
  test in `PackagePolicyTest` enforce rule 3. The guard lands in Phase 2 step 4, together with
  the single impl context helper that is allowlisted to use `ContextInternal`.
- Virtual-thread tests are tagged `@Tag("virtual-threads")`. They are excluded only in the JDK 11 job.
- Test code compiles with `--release 11`, same as main. Virtual-thread verticles are deployed
  through the Vert.x API only (`ThreadingModel.VIRTUAL_THREAD`), never through JDK
  virtual-thread APIs.
- The payload-signing matrix is covered against Silo: HTTPS (unsigned streaming/multipart,
  signed small requests, signed above the `executeBlocking` threshold) and plain HTTP
  (signed only, unsigned rejected). HTTPS uses a committed, test-only self-signed certificate
  with a long expiry under `src/test/resources` (named so it is obviously test-only).
- Streaming tests cover backpressure (slow consumer), empty stream, exact part-size multiples,
  failures, and abort.
- Test data is generated at runtime: random bytes of exact sizes (e.g. 0, 1 byte, 5 MiB,
  n × 5 MiB, 5 MiB + 1). Never commit large binary fixtures.
- `PackagePolicyTest` enforces the package layout on the compiled classes: everything is in
  the API, `impl` or `examples` package; API types are public and `S3`-prefixed; no API
  signature mentions an impl type; impl classes are package-private unless allowlisted; the
  module exports only the API package; API names don't clash with JDK or Vert.x public types.
- The signer has unit tests against the AWS SigV4 test suite, vendored from botocore under
  `src/test/resources/aws4-testsuite` (Apache-2.0, with its LICENSE and NOTICE; don't edit the
  vectors). S3-specific rules are unit-tested and verified against Silo, which checks signatures
  server-side.
- Presigning has unit tests against the AWS SigV4 published presign examples, using a fixed
  `Clock`. Integration tests download and upload through presigned URLs against Silo, using a
  plain HTTP client.
- XML tests cover escaping and rejection of DTD/external-entity payloads.
- No `*Blocking` calls on event-loop threads in tests. Tests are independent (no ordering, no
  shared static client state across test classes).

## CI
- JDK 21 job: `mvn verify`, the full suite including virtual-thread tests.
- JDK 11 job: `mvn verify` with virtual-thread tests excluded. It proves the main artifact
  builds and the rest of the suite passes on 11.
- Both jobs must pass without `-Dapi.version` or other Docker workarounds.

## Branch & commit conventions
- Work on feature branches off `revamping` (or `master` after the merge). Never commit to `master` directly.
- Small, focused commits, imperative subject (≤72 chars), body explains why.
- Mark breaking API changes with `BREAKING:` in the commit body.

## Do / Don't for AI agents
**Do**
- Read this file and the relevant code before editing. Keep changes scoped to the task.
- Add or adjust tests (including context tests) with every behavior change. Run `mvn verify`.
- Prefer deleting dead code over preserving it. There are no compatibility obligations.
- Ask before adding any dependency, using an `io.vertx.core.internal` API not listed above,
  or changing public API shape.

**Don't**
- Don't introduce callbacks, `CompletableFuture`, blocking calls, threads, executors,
  or static state.
- Don't use WebClient, Jackson, or Vert.x `impl` packages outside `io.vertx.core.internal`.
- Don't use Java 12+ language features or APIs in `src/main` or `src/test`.
- Don't make an impl class public, or reference an impl type from an API signature, to "make
  it work". Fix the design, or add an allowlist entry with a reason, after asking.
- Don't remove, replace or restructure build, site or doc tooling (docgen, asciidoctor, javadoc,
  publishing) unless a recorded decision requires it or you are explicitly asked.
- Don't commit, push, tag, or release unless explicitly asked.
- Don't commit secrets, real endpoints, or large binary fixtures. Generate test data at runtime.
- Don't edit generated sources (`*Converter.java`).
