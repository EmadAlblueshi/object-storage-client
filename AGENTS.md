# AGENTS.md — vertx-s3-client

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

## Purpose
Asynchronous, idiomatic Vert.x 5 client for Amazon S3 and S3-compatible storage
(e.g. DigitalOcean Spaces; MinIO-compatible servers for local dev and tests).

**Rename in progress:** formerly `object-storage-client`
(`io.github.emadalblueshi.objectstorage.client`). The old API is being replaced, not
migrated; backward compatibility is NOT a goal. Do not add code to the old package.

**Roadmap:** [`docs/REVAMP_PLAN.md`](docs/REVAMP_PLAN.md) is the source of truth for the
revamp roadmap, phases and recorded decisions. Follow it; when it and this file disagree, ask.

**Releases:** nothing is published until the gates in the "1.0.0 release gates" section of
[`docs/REVAMP_PLAN.md`](docs/REVAMP_PLAN.md) pass. The next version is `1.0.0-RC1`, then `1.0.0`.
Only the owner relaxes or drops a gate. After `1.0.0-RC1`, any public API change means a new RC.

## Tech stack
- Maven, single module.
- **Java:** main and test code both compile with `--release 11` (the Vert.x 5 baseline;
  `maven.compiler.release` 11, no `testRelease`). The full suite runs on JDK 21.
- **Vert.x 5.2.x, pinned to an exact version** in the pom (currently 5.2.3). Transport is
  `io.vertx.core.http.HttpClient`. No WebClient.
- **`vertx-core` is the only runtime dependency.** XML uses JDK StAX (`java.xml`); no Jackson
  XML, no other libraries.
- `vertx-codegen` (provided scope): `@DataObject` + generated JSON converters for options classes,
  and `@VertxGen` on every API interface, so the processor rejects codegen-illegal signatures at
  compile time (no `Instant`, `Duration`, `URI`, `Clock` or own functional interfaces in method
  signatures). Use codegen's `@Nullable`, `@Fluent` and `@CacheReturn` as Vert.x's own clients do.
  A Java-only convenience overload with such a type is allowed only next to a codegen-legal
  method that does the same, annotated `@GenIgnore(GenIgnore.PERMITTED_TYPE)` as in Vert.x core
  (e.g. the presigner's `Duration` overloads).
- `vertx-docgen` (provided scope): generates the Asciidoc manual from `src/main/asciidoc/*.adoc`
  and the `examples` package. Both processors run via `annotationProcessorPaths`. The manual's
  `${maven.groupId}`, `${maven.artifactId}` and `${maven.version}` are filled in by docgen from
  `-Amaven.*` processor options in the pom, as in the official Vert.x manuals. Docgen reads these
  options without declaring them, so javac's "The following options were not recognized by any
  processor" warning is expected (owner decision, 2026-11-21); don't remove the options to silence it.
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
  `PackagePolicyTest`, each with a reason. Currently there's one: `impl.S3ClientImpl`, which
  `S3Client.create` instantiates.
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
  it**: removing web-client and Jackson, the Testcontainers/JUnit versions, `--release 11`, the
  processor wiring for codegen and docgen, and the filtered `src/main/resources-filtered`
  directory that supplies the `User-Agent` version.
- Leave the rest of the existing build, site and doc setup as it is: docgen, the asciidoctor
  site, javadoc, the source and test jars, Central publishing and the gpg profile.

## Commands
- Compile: `mvn -q -DskipTests compile`
- Full suite (JDK 21, needs Docker): `mvn verify`
- JDK 11 check: `mvn verify` on JDK 11. The `java11` profile auto-activates and only excludes
  the `virtual-threads` tag; it changes nothing else.
- Single test: `mvn test -Dtest=ClassName#method`
- External backend profile (opt-in, run locally by the owner; no CI job): set `S3_ENDPOINT`,
  `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, then
  `mvn test -Dtest='S3Client*ExternalTest' -Dsurefire.failIfNoSpecifiedTests=false`. Without all
  four, the external tests are reported as skipped. Optional, test-only `S3_PATH_STYLE=false` runs
  them with virtual-hosted-style addressing (default: path-style).
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
   presigning: it is pure computation with no I/O, so it is synchronous and returns the URL
   as a `String` directly (see "Presigned URLs").
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
   closed when that context closes (a registered resource, as for Vert.x's own clients).
7. **Prefer Vert.x primitives:** `HttpClient`, `Buffer`, `Future` composition
   (`compose`, `transform`, `eventually`, `recover`), Vert.x timers when timing is needed,
   JsonObject-backed `@DataObject` options classes with a copy constructor plus `toJson`/`fromJson`.
8. Public async methods never throw; validation failures return a failed Future.

## Vert.x internal API policy
- `io.vertx.core.internal` (`ContextInternal`, `PromiseInternal`, `VertxInternal`,
  `CloseableResource`) is allowed **only inside `impl` packages**, never in the public API's
  signatures.
- Use it for context-bound promises (`ContextInternal.promise()`) and resource registration
  (`VertxInternal.registerResource`, exactly as Vert.x's own `HttpClient` registers itself),
  **only inside `impl.ContextSupport`**, the single class that may import `io.vertx.core.internal`
  (enforced by the rule-3 guard). Everything else goes through `ContextSupport.Caller` (`bind`,
  `succeeded`, `failed`, `executeBlocking`, `promise`, `onContext`, `runOnContext`) and
  `ContextSupport.Registration`. An explicit `close()` goes through the registration, which
  unregisters, so a closed client isn't referenced by its context until undeploy. Any other
  internal API needs explicit approval.
- **On every Vert.x version bump, re-verify every internal API usage** (compile, plus the
  context test suite on all three threading models) before merging.

## No deprecated APIs
- No deprecated Vert.x or JDK APIs in `src/main` (including `forRemoval` ones). When an API is
  deprecated, move to its documented replacement in a small, separate commit.
- Enforced mechanically by `NoDeprecatedApiTest`: it compiles `src/main` (plus the generated
  converters) again with the JDK's `javac`, in a child process, with `-Xlint:deprecation` and
  `-proc:none`, and fails on every `[deprecation]` or `[removal]` warning. Other warnings aren't
  its concern, and tests aren't checked. No build configuration is involved.
- Why not `-Werror` on the build's own compile: it always fails on the third-party docgen
  processor's own warning ("Supported source version 'RELEASE_8' … less than -source '11'"),
  which no lint switch silences. A second `maven-compiler-plugin` execution works, but the
  `compile` goal always re-points the project artifact at its own output directory (a Maven
  warning on every build). The check runs `javac` as a child process because the tests run inside
  the library's module, which doesn't read `java.compiler`.

## Payload-signing policy
- **Not configurable**: there is no public setting. The endpoint scheme decides
  (`impl.PayloadMode`, decision 4 revised as Option A):
  - HTTPS: `UNSIGNED-PAYLOAD` for every request, buffered or streamed. Nothing is hashed.
  - Plain HTTP: payloads are always signed with SHA-256. Buffers above a fixed internal
    threshold (256 KiB) are hashed via `executeBlocking`, never inline on the event loop.
    Upload parts are hashed incrementally as their chunks arrive.
- Don't reintroduce a payload-signing option unless the owner asks (e.g. for a backend that
  demands signed payloads over HTTPS, or a TLS-terminating proxy).
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
- Multipart is internal only: `putObject` from a stream or file uses it when required, and no
  multipart type or method is public. Abort on every failure path; close every opened
  `AsyncFile`; an empty or small body uses a single PutObject.
- Large objects are never buffered in memory. `getObject` (Buffer) is for small objects only and
  is size-capped: it fails with an `S3ClientException` pointing to `getObjectStream` instead of
  buffering a body over the cap. `getObjectStream` streams the body with backpressure.
- Every buffered response body is size-limited (`impl.Transport`): `maxBufferedObjectSize` for
  `getObject`, a fixed internal 4 MiB for XML results, and error bodies truncated at 4 MiB. Once a
  body crosses its limit (or its `Content-Length` announces it), the outcome is decided: the result
  (limit error or truncated body) is recorded first, later data, end and failure events are
  ignored, and the request is cancelled best-effort, which closes the connection if the response is
  still in flight. A failure of that cancel (e.g. the response already ended) is ignored. The same
  rule applies to `S3ObjectStream.cancel()`, whose Future never fails.
  A HEAD response's `Content-Length` describes the object and never counts as a body.
- The transport assembles the whole exchange in one task on the caller's context
  (`Caller.onContext`) before the request is sent: signing, sending, and every continuation that
  consumes the response. So the response head reaches them inline in the task that delivers it,
  and body handlers are attached before any body event. Never attach a response-consuming
  continuation from another thread (e.g. a non-Vert.x caller's): it may run in a later task, after
  Vert.x has dropped the body and ended the response ("Response already ended"). Response events and
  object-stream handlers run on the caller's context (rule 4).
- `S3ObjectStream` is handed out paused, with its own handlers attached to the response at once
  (Vert.x drops chunks that arrive with no handler set). It delivers at most one terminal event
  (end, failure or cancellation). `cancel()` closes the connection and fails the stream with an
  `S3ClientException`, so pipes don't hang. Object streams must be consumed or cancelled; docs
  and examples always show the cancel on paths that don't consume. That includes a failed
  `pipeTo` and, for an `HttpServerResponse`, the response's close handler: `send(stream)` doesn't
  notice a disconnected HTTP client until a write fails (Vert.x's pipe doesn't watch the
  destination), and on a write failure Vert.x resumes the stream and drains the object.
- ListObjectsV2 always sends `encoding-type=url`. Keys and prefixes are decoded (form-style:
  `+` is a space) only when the response says `<EncodingType>url</EncodingType>`. There is no
  paging helper: the caller pages with the continuation token.
- Object keys are validated before any I/O (`impl.ObjectRequests`): 1 to 1024 bytes of valid
  UTF-8. So are ranges (a single `bytes=` range), content types and user metadata (token names,
  case-insensitive; printable US-ASCII values).
- Bucket names are validated before any I/O (`impl.BucketNames`): the general S3 rules, plus no
  dotted names with virtual-hosted-style access over HTTPS. An invalid name is a failed Future
  with an `S3ClientException`.
- Every request carries `User-Agent: vertx-s3-client/<version>` (unsigned). The version is
  filtered into `impl/version.properties` at build time and never hard-coded.
- Errors map to the library's `S3Exception` hierarchy: `S3ServiceException` for error responses
  (HTTP status, S3 code, request ids, details; bodiless and non-XML responses included) and
  `S3ClientException` for everything else (connection, TLS, unreadable response, invalid argument,
  closed client). Redirects are never followed. `S3Exception` extends `VertxException`;
  `S3ServiceException` carries no stack trace (as Vert.x's server-reported errors), while
  `S3ClientException` keeps it.

## Presigned URLs
- A synchronous `@VertxGen` API returning the URL as a `String`, with no `Future`. It's usable
  standalone via `S3Presigner.create(options)` without a `Vertx` instance, and is also exposed
  from the client.
- Signatures (owner decision, 2026-11-21, audit R4 as revised): the expiry is an amount and a
  `TimeUnit`, in Vert.x's order (as `Future.timeout(long, TimeUnit)`), plus Java-only `Duration`
  overloads marked `@GenIgnore(GenIgnore.PERMITTED_TYPE)`:
  `presignGet`/`presignPut(String bucket, String key, long expiry, TimeUnit unit)` and
  `presignGet`/`presignPut(String bucket, String key, Duration expiry)`. Both forms validate the
  expiry: positive, a whole number of seconds, at most 7 days (604800 s). No `URI`, `Instant`,
  `Clock` or `Duration` in any other public signature.
- Query-string auth with `UNSIGNED-PAYLOAD`, through the shared signer, with the static
  credentials of its options (there is no credentials provider).
- The presigner endpoint is configurable separately from the client endpoint (public host vs
  internal Kubernetes address). Host is signed with the port when it's non-default.
- GET and PUT. Only `host` is signed by default; optional extra signed headers (e.g.
  `Content-Type`) are allowed. `response-*` overrides, `X-Amz-Security-Token` support, and
  RFC 3986 key encoding with `/` preserved.
- Time comes from the system UTC clock. A `java.time.Clock` is only an impl test hook (a
  package-private constructor), never part of the public API.
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
- **Credentials are static** (owner decision, 2026-11-21, audit R3):
  `S3ClientOptions.setCredentials(S3Credentials)` is the only way to configure them, and it is
  fully JSON (`new S3ClientOptions(config.getJsonObject("s3"))`). The client copies them at
  creation; to change keys, create a new client. No credentials provider, `Supplier` overload or
  other rotation mechanism unless the owner asks (then as an overload).

## Testing rules
- **No flaky tests.** Every test must be deterministic regardless of machine or runner speed:
  control the ordering of events explicitly (e.g. a stub that withholds a response until the
  client acts, or a fake that delivers events in a scripted order), never rely on timing. A flaky
  test is treated as a bug in the code or the test and fixed at its root cause; it is never
  retried, re-run to green, or disabled. Timeouts are only a safety net that turns a hang into a
  failure, never part of what a test checks.
- vertx-junit5. Integration tests run against Silo via Testcontainers. The image is pinned to an
  exact release tag (never `latest`); don't change the tag without asking. Prefer the JVM-wide
  `S3TestBackend.sharedSilo()`/`sharedSiloTls()` (never stop them; use unique bucket names) over
  starting a container per test class.
- **Silo is the only container backend.** Integration tests are backend-agnostic: `S3TestBackend`
  supplies the endpoint, region, credentials and TLS. Besides Silo (HTTP and HTTPS) there is one
  opt-in, generic `ExternalBackend`, configured only by the `S3_*` environment variables (no
  provider names in variables, classes or tests). Backend credentials are never committed.
- Suites run against several backends without duplication: an abstract `*TestBase` holds the
  tests; one subclass per backend (`*SiloTest`, `*SiloTlsTest`, `*ExternalTest`) supplies it.
  Backend differences are explicit expectations in the subclasses, never skipped assertions.
  External subclasses are `@EnabledIf` on a local `configured()` method, so they are skipped,
  never failed, when the variables are missing.
- **Tests must be safe on a real account:** buckets get unique `vertx-s3-it-` names
  (`TestResources.uniqueBucketName()`); every bucket and key is registered with `TestResources`
  before it is created, and cleanup deletes everything even when tests fail. Keep bucket
  creation to a minimum (providers rate-limit it): object tests share one suite bucket; checks
  that need extra buckets stay in the Silo subclasses.
- Every public async operation has a context test: from an **event-loop verticle**, a
  **worker verticle**, and a **virtual-thread verticle**, assert that
  `Vertx.currentContext()` inside every callback / Future completion is the same instance as
  the context captured before the call. Cover failures as well as successes.
- The harness complements the context-bound-Futures rule (Vert.x rule 3) but can't replace it.
  It can't detect timing-dependent violations: if a Future is already complete when the harness
  attaches its listener, the listener runs synchronously on the caller's thread and the check
  passes, even if the implementation used a context-less promise. Review and the rule-3 guard
  test in `PackagePolicyTest` enforce rule 3. The guard scans main sources for context-less
  Future factories and confines `io.vertx.core.internal` to `impl.ContextSupport`.
- Virtual-thread tests are tagged `@Tag("virtual-threads")`. They are excluded only in the JDK 11 job.
- Test code compiles with `--release 11`, same as main. Virtual-thread verticles are deployed
  through the Vert.x API only (`ThreadingModel.VIRTUAL_THREAD`), never through JDK
  virtual-thread APIs.
- Payload signing is covered against Silo: HTTPS (unsigned, buffered and streamed) and plain
  HTTP (signed: inline, and above the `executeBlocking` threshold). HTTPS uses a committed,
  test-only self-signed certificate with a long expiry under `src/test/resources` (named so it
  is obviously test-only).
- Streaming tests cover backpressure (slow consumer), empty stream, exact part-size multiples,
  failures, and abort.
- Test data is generated at runtime: random bytes of exact sizes (e.g. 0, 1 byte, 5 MiB,
  n × 5 MiB, 5 MiB + 1). Never commit large binary fixtures.
- `PackagePolicyTest` enforces the package layout on the compiled classes: everything is in
  the API, `impl` or `examples` package; API types are public and `S3`-prefixed; no API
  signature mentions an impl type; impl classes are package-private unless allowlisted; the
  module exports only the API package; API names don't clash with JDK or Vert.x public types;
  every public API interface is `@VertxGen`.
- The signer has unit tests against the AWS SigV4 test suite, vendored from botocore under
  `src/test/resources/aws4-testsuite` (Apache-2.0, with its LICENSE and NOTICE; don't edit the
  vectors). S3-specific rules are unit-tested and verified against Silo, which checks signatures
  server-side.
- Presigning has unit tests against the AWS SigV4 published presign examples, using a fixed
  `Clock` through the impl test hook. Integration tests download and upload through presigned
  URLs against Silo, using a plain HTTP client.
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
- No `Co-Authored-By` or other trailers in commit messages.

## Do / Don't for AI agents
**Do**
- Read this file and the relevant code before editing. Keep changes scoped to the task.
- Add or adjust tests (including context tests) with every behavior change. Run `mvn verify`.
- Prefer deleting dead code over preserving it. There are no compatibility obligations.
- Ask before adding any dependency, using an `io.vertx.core.internal` API not listed above,
  changing public API shape, or adding a public method or option the scope section doesn't
  cover.

**Don't**
- Don't add anything listed under non-goals, or AWS SDK-style configurability "for
  completeness".
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
