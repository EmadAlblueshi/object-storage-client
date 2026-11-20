# AGENTS.md — vertx-s3-client

## Purpose
Asynchronous, idiomatic Vert.x 5 client for Amazon S3 and S3-compatible storage
(e.g. DigitalOcean Spaces; MinIO for local dev/tests).

**Rename in progress:** formerly `object-storage-client`
(`io.github.emadalblueshi.objectstorage.client`). The old API is being replaced, not
migrated; backward compatibility is NOT a goal. Do not add code to the old package.

## Tech stack
- Maven, single module.
- **Java:** main and test code both compile with `--release 11` (the Vert.x 5 baseline;
  `maven.compiler.release` 11, no `testRelease`). The full suite runs on JDK 21.
- **Vert.x 5.2.x, pinned to an exact version** in the pom (currently 5.2.3). Transport is
  `io.vertx.core.http.HttpClient`. No WebClient.
- **`vertx-core` is the only runtime dependency.** XML uses JDK StAX (`java.xml`); no Jackson
  XML, no other libraries.
- `vertx-codegen` (provided scope): `@DataObject` + generated JSON converters for options classes.
- Tests: JUnit Jupiter **5.14.4** (`junit-bom` imported BEFORE `vertx-dependencies`; don't
  take the JUnit 6 that the Vert.x BOM manages, because it needs Java 17), `vertx-junit5`,
  Testcontainers **2.0.5** (works with Docker 29), MinIO.

## Coordinates & layout
- Maven: `io.github.emadalblueshi:vertx-s3-client`
- Module: `io.github.emadalblueshi.vertx.s3` (exports the API package only)
- `io.github.emadalblueshi.vertx.s3` — public API (client interface, options, results, exceptions)
- `io.github.emadalblueshi.vertx.s3.impl` (and sub-packages) — internals (signer, transport,
  XML, streams). Never exported.

## Commands
- Compile: `mvn -q -DskipTests compile`
- Full suite (JDK 21, needs Docker): `mvn verify`
- JDK 11 check: `mvn verify` on JDK 11. The `java11` profile auto-activates and only excludes
  the `virtual-threads` tag; it changes nothing else.
- Single test: `mvn test -Dtest=ClassName#method`
- Format check: `mvn spotless:check` (once configured)

## Java 11 rules (`src/main` and `src/test`)
- No records, sealed types, switch expressions, text blocks, or pattern matching for `instanceof`.
- No post-11 JDK APIs (`HexFormat`, `Stream.toList()`, `String.formatted`, `Thread.ofVirtual`, …).
  `--release 11` enforces the API part.
- Virtual-thread support comes only through Vert.x (`ThreadingModel.VIRTUAL_THREAD` on the
  caller's side). The library never references virtual-thread JDK APIs.

## Vert.x rules (NON-NEGOTIABLE)
1. **Future-only public API** (Vert.x 5 style). No callback overloads, no
   `CompletableFuture`, no blocking methods in the public API.
2. **Context capture:** every public async method captures the caller's context at call
   time and completes its Future ON THAT CONTEXT, whether the caller is an event loop, a
   worker, or a virtual-thread verticle. Never use an unbound `Promise.promise()` for a
   result that crosses an async boundary.
3. **Streams:** `ReadStream`/`WriteStream` handlers (`handler`, `endHandler`,
   `exceptionHandler`, `drainHandler`) run on the owning context, with real backpressure
   (`pause`/`resume`/`fetch`, `pipeTo`). Pause any user-supplied stream BEFORE the first
   async step. Never emit while paused.
4. **Never block the event loop:** no blocking file I/O (use `FileSystem`/`AsyncFile`),
   no `*Blocking` calls, no heavy inline CPU (see the payload-signing policy).
5. **No own threads, executors, or static `Vertx`.** The client is bound to the `Vertx`
   passed in. `close()` returns `Future<Void>`. A client created inside a verticle is
   closed when that context closes (close hook).
6. **Prefer Vert.x primitives:** `HttpClient`, `Buffer`, `Future` composition
   (`compose`, `transform`, `eventually`, `recover`), Vert.x timers for retries/timeouts,
   JsonObject-backed `@DataObject` options classes with a copy constructor plus `toJson`/`fromJson`.
7. Public async methods never throw; validation failures return a failed Future.

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
- Multipart: abort on every failure path; close every opened `AsyncFile`; an empty or small body
  uses a single PutObject.
- Errors map to the library's `S3Exception` hierarchy (HTTP status, S3 code, requestId);
  handle bodiless (HEAD) and non-XML error responses.

## XML
- JDK StAX only. Parsers disable DTDs and external entities
  (`SUPPORT_DTD=false`, `IS_SUPPORTING_EXTERNAL_ENTITIES=false`).
- Request bodies are written with proper XML escaping of all text and attribute values
  (keys, ETags, tags). Never build XML by unescaped string concatenation.

## Security
- Never log credentials, `Authorization`, `x-amz-security-token`, or presigned URLs.
- Options `toJson()`/`toString()` must not expose the secret key.
- No default credentials; fail fast when they are missing.

## Testing rules
- vertx-junit5. Integration tests run against MinIO via Testcontainers (pinned image tag).
- Every public async operation has a context test: from an **event-loop verticle**, a
  **worker verticle**, and a **virtual-thread verticle**, assert that
  `Vertx.currentContext()` inside every callback / Future completion is the same instance as
  the context captured before the call.
- Virtual-thread tests are tagged `@Tag("virtual-threads")`. They are excluded only in the JDK 11 job.
- Test code compiles with `--release 11`, same as main. Virtual-thread verticles are deployed
  through the Vert.x API only (`ThreadingModel.VIRTUAL_THREAD`), never through JDK
  virtual-thread APIs.
- The payload-signing matrix is covered against MinIO: HTTPS (unsigned streaming/multipart,
  signed small requests, signed above the `executeBlocking` threshold) and plain HTTP
  (signed only, unsigned rejected). HTTPS uses a committed, test-only self-signed certificate
  with a long expiry under `src/test/resources` (named so it is obviously test-only).
- Streaming tests cover backpressure (slow consumer), empty stream, exact part-size multiples,
  failures, and abort.
- The signer has unit tests against the AWS SigV4 test vectors (no Docker).
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
- Don't commit, push, tag, or release unless explicitly asked.
- Don't commit secrets, real endpoints, or large binary fixtures. Generate test data at runtime.
- Don't edit generated sources (`*Converter.java`).
