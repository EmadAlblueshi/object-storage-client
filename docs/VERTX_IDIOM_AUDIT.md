# Vert.x idiom audit (2026-11-21)

**Goal:** a Vert.x developer feels at home. The library should look, feel and behave like an
official Vert.x client, with nothing AWS-SDK-flavoured. This audit is release gate 2 in
[`REVAMP_PLAN.md`](REVAMP_PLAN.md) ("1.0.0 release gates").

**Method:**
- **References:** the official sources at tag `5.2.3`: `eclipse-vertx/vert.x` (core),
  `eclipse-vertx/vertx-sql-client` (sql, pg), `vert-x3/vertx-mail-client`,
  `vert-x3/vertx-redis-client`, `eclipse-vertx/vertx-codegen` and `vert-x3/vertx-lang-kotlin`,
  plus `smallrye/smallrye-mutiny-vertx-bindings` 4.1.1 (built on Vert.x 5.2.3). File references
  below are relative to those repositories.
- **Codegen claims were compiled, not assumed:** a copy of the library at `38c3f56` with
  `@VertxGen` added, plus deliberately illegal probe methods.
- **The UX program** (item 7) was written and compiled in four styles against the library at
  `38c3f56`.

## Owner decisions (2026-11-21)
Where a decision differs from the recommendation in the item below, the decision wins.

| # | Decision | Differs from the recommendation? |
|---|---|---|
| R1 | `@VertxGen` on `S3Client` and `S3ObjectStream`; `@CacheReturn` on `metadata()`; explicit `@Fluent` on the `ReadStream` overrides; codegen `@Nullable` on the `putObject` ETag (`Future<@Nullable String>`) and on the nullable options parameters of `putObject` and `listObjects`. | No |
| R2 | `S3Exception extends VertxException`. `S3ServiceException` is created without a stack trace; `S3ClientException` keeps its stack trace. | No |
| R3 | Credentials are **static only**, set on the options with `setCredentials(S3Credentials)`: the single way to configure them, and fully JSON (`new S3ClientOptions(config.getJsonObject("s3"))`). `S3CredentialsProvider` and the `credentialsProvider` options field are deleted. **No `Supplier` overload**: rotation can be added later as an overload if the owner asks; to change keys, create a new client. The presigner uses the options' static credentials. | **Yes**: the recommendation was a `create(vertx, options, Supplier<S3Credentials>)` overload. |
| R4 | Phase 3 presigner: returns `String`, expiry as `long` seconds, `Clock` only as an impl test hook, all signatures codegen-legal (no `Instant`/`Duration`/`URI`/`Clock` in `@VertxGen` method signatures). | No (the `@GenIgnore` `URI` variant is not taken) |
| R5 | Manual: `send(stream)` + `Content-Length` + cancel on failure for HTTP responses; open the file before getting the stream; Maven/Gradle dependency block; fold the AWS SDK section into a NOTE; drop the WARNING banner at RC1. Also document that Mutiny `Multi` / `MultiHelper.toMulti` cancellation resumes rather than cancels, and recommend calling `cancel()` explicitly. | Adds the Mutiny note |
| R6 | Rename `metadata` → `userMetadata` in `S3ObjectMetadata` and `S3PutObjectOptions`. It stays `Map<String, String>` (JSON- and codegen-friendly; S3 metadata is single-valued, so no `MultiMap`). `S3PutObjectOptions` gets a fluent `putUserMetadata(String name, String value)` (adds or replaces one entry, same validation and case-insensitive duplicate handling) next to `setUserMetadata(Map)`. Codegen must not treat it as a JSON adder (`@GenIgnore` if needed). `S3ObjectMetadata.getUserMetadata()` stays an unmodifiable map with lowercase keys. | Adds `putUserMetadata` |

**Not done:** a builder type, `createShared`, Mutiny or Kotlin artifacts, unwrapping transport
exceptions.

**Order:** separate commits, each reviewed diff first: R1 + R2, then R3 + R6, then R5, then R4.

---

## 1. `@VertxGen` and whether our signatures are codegen-legal
- **Official:** every API interface is `@VertxGen`: `MailClient`, `Redis`, `RowStream`,
  `HttpClientResponse`. The codegen processor rejects any type outside the Vert.x vocabulary at
  compile time.
- **Ours (at `38c3f56`):** nothing is `@VertxGen`.
- **Prototype:** `@VertxGen` on `S3Client` and `S3ObjectStream` compiled with no other change.
  - A deliberately illegal probe method was rejected ("type java.time.Instant is not legal for
    use for a return type in code generation"), so the processor really checks, and our
    signatures pass.
  - `Instant` fields inside data objects are fine: the generated converters use ISO-8601.
- **Not legal in `@VertxGen` method signatures (probed):**
  - `S3CredentialsProvider` (our own functional interface) as a parameter. It only appears inside
    the options class, marked `@GenIgnore`, so nothing breaks today. `Supplier<S3Credentials>` and
    `Supplier<Future<S3Credentials>>` are legal.
  - `java.net.URI`, `Duration`, `Clock`, `Instant`. That matters for the Phase 3 presigner (R4).
  - `Map<String, String>` and `List<String>` are legal.
- **Recommendation:** add `@VertxGen` to both interfaces. It's the official look, and it turns
  the codegen checker into a compile-time guard that keeps the API inside the Vert.x vocabulary.
- **Cost:** two annotations; no runtime change.

## 2. `@Fluent`, `@Nullable`, `@CacheReturn`
- **`@Fluent`:**
  - **Official:** codegen inherits `@Fluent` from the overridden `ReadStream` methods
    (`vertx-codegen-processor/.../ClassModel.java:709`, `Helper.resolveMethodAnnotation`). Core's
    `HttpClientResponse` overrides don't repeat it; sql's `RowStream` does
    (`vertx-sql-client/.../sqlclient/RowStream.java:42`).
  - **Ours:** `S3ObjectStream` already redeclares every fluent method, without `@Fluent`.
  - **Recommendation:** explicit `@Fluent` as `RowStream` does. Optional, free.
- **`@Nullable`:**
  - **Official:** core marks `Future<@Nullable T>` (`vertx-core/.../core/Vertx.java:831`,
    `executeBlocking`) and nullable parameters.
  - **Ours:** none. Nullable today: the `putObject` ETag, the `listObjects` options parameter and
    the `putObject` options parameter.
  - **It changes Kotlin types (checked with kotlinc 2.4):** against the annotated prototype,
    `val etag: String = s3.putObject(...).coAwait()` warns "inferred type is 'String?'". Against
    the unannotated classes it compiles silently and fails at runtime when the server sends no
    ETag.
  - **Recommendation:** add them. Three annotations.
- **`@CacheReturn`:**
  - **Official:** core uses it on accessors that always return the same object
    (`vertx-core/.../http/HttpClientResponse.java:58,72,118`: `netSocket()`, `trailers()`,
    `request()`).
  - **Ours:** `S3ObjectStream.metadata()` always returns the same object.
  - **Recommendation:** add it. Free.

## 3. Data objects
- **Official:** `@DataObject` + `@JsonGen(publicConverter = false)`, a `JsonObject` constructor,
  a copy constructor, `toJson`, fluent setters and `DEFAULT_*` constants.
  - Core: `HttpClientOptions`, `PoolOptions`, `RequestOptions`. Sql: `PgConnectOptions`,
    `PoolOptions`, and the result object `PgNotification`. Redis: `RedisOptions`.
  - Mail hand-writes its JSON instead (`MailConfig`, `MailResult`).
  - `RedisOptions` composes `NetClientOptions` and `PoolOptions`, exactly as we compose
    `HttpClientOptions` and `PoolOptions`. (`MailConfig` extends `NetClientOptions`.)
- **Ours:** the same pattern. **No deviation**, with two exceptions.
- **Exception 1, a function inside the options:**
  - **Ours:** `S3ClientOptions.credentialsProvider`, which can't be written to JSON, marked
    `@GenIgnore`.
  - **Official:** options stay purely JSON; functional configuration goes elsewhere: mail's
    `MailClientBuilder.withCredentialsSupplier(Supplier<Future<…>>)`
    (`vertx-mail-client/.../MailClientBuilder.java:33`), sql's
    `connectingTo(Supplier<Future<SqlConnectOptions>>)`, redis's
    `createStandaloneClient(vertx, options, Supplier<Future<…>>)`
    (`vertx-redis-client/.../Redis.java:93`).
  - **Recommendation:** see item 4. **Decided:** R3.
- **Exception 2, `toJson()` omits secrets:**
  - **Ours:** a JSON round trip loses the secret key and session token. Loading from JSON config
    works: the `S3Credentials(JsonObject)` constructor reads them.
  - **Official:** core writes passwords in clear.
  - **Recommendation:** keep ours (recorded security decision). The javadoc should say
    `toJson()` is for logging and inspection, not for copying credentials.

## 4. `create(Vertx, options)` or a Vert.x 5 builder
- **Official:**
  - All keep the create-from-options form: `MailClient.create`
    (`vertx-mail-client/.../MailClient.java:57`), `Redis.createClient`,
    `vertx.createHttpClient`, `Pool.pool`.
  - Builders exist in mail (`MailClient.builder`, `MailClient.java:46`), core
    (`vertx.httpClientBuilder()`) and pg (`PgBuilder.pool()`/`client()`).
  - A builder appears exactly when there is non-JSON configuration: credential suppliers,
    connect handlers, several databases.
- **Ours:** `create(Vertx, S3ClientOptions)`, with the provider inside the options.
- **Recommendation:** keep `create`, no builder type. Move the provider out of the options as an
  overload, redis-style: `create(Vertx, S3ClientOptions, Supplier<S3Credentials>)`, and delete
  `S3CredentialsProvider`.
- **Decided (R3):** static credentials only, no overload for now.
- **Shared pools:** should already work by setting `setShared(true)`/`setName(...)` on the
  composed `HttpClientOptions` (`vertx-core/.../http/HttpClientOptions.java:1100,1117`), because
  the transport calls `vertx.createHttpClient(options, poolOptions)`. Untested, so no
  `createShared`.

## 5. Exception hierarchy
- **Official:** everything extends `VertxException`: sql's `DatabaseException` (which
  `PgException` extends), mail's `SMTPException` (`vertx-mail-client/.../SMTPException.java:29`),
  core's `HttpClosedException` and `ConnectionPoolTooBusyException`. Server-reported errors skip
  the stack trace (`super(message, true)` in `vertx-sql-client/.../DatabaseException.java:26` and
  `vertx-core/.../http/HttpClosedException.java:31`): on an event loop it is noise and
  allocation under load.
- **Ours:** `S3Exception extends RuntimeException`; every error captures a stack trace.
- **Recommendation (R2):** `S3Exception extends VertxException`; `S3ServiceException` without a
  stack trace (its request id is the useful handle); `S3ClientException` keeps it (a validation
  failure's trace points at the caller). Existing `catch (RuntimeException)` code keeps working.
- **Naming:** the service-vs-client split mirrors pg (`PgException` vs other failures). The names
  echo the AWS SDK (`SdkServiceException`/`SdkClientException`), but there's no better Vert.x
  convention, so keep them. Unwrapping transport errors as core does would lose the single catch
  type; not recommended.

## 6. `@ModuleGen` and module-info
- **Official:** mail has `@ModuleGen(name = "vertx-mail", groupPackage = "io.vertx")`
  (`vertx-mail-client/.../mail/package-info.java:16`) and `requires static` for codegen.api,
  codegen.json and docgen.
- **Ours:** `@ModuleGen(name = "vertx-s3-client", groupPackage = "io.github.emadalblueshi")`, the
  same `requires static` lines. **No change.**
- **`groupPackage`** decides where generated wrappers land: a Mutiny wrapper would be
  `io.github.emadalblueshi.mutiny.vertx.s3.S3Client`
  (`vertx-mutiny-code-generator/.../ShimConstants.java:9`).
- **One difference:** ours has `requires transitive io.vertx.core`; mail's isn't transitive.
  Ours is more correct, because our API exposes Vert.x types.

## 7. UX: the same program in four styles
The program creates a client from JSON config, uploads, lists a prefix, streams a download to an
`AsyncFile` and to an `HttpServerResponse`, and closes.

- **Future composition:** idiomatic; nothing impossible. Three findings:
  1. **A disconnected HTTP client makes us download the rest of the object.** When the response
     write fails, Vert.x's pipe detaches and resumes the source
     (`vertx-core/.../streams/impl/PipeImpl.java:102-103`: `if (err instanceof WriteException)
     src.resume()`). The rest of a multi-GB object is then read from S3 for nothing. It does
     release the connection in the end; it's parity with core's `HttpClientResponse`, but costly.
     - Fix: `resp.send(stream).onFailure(e -> stream.cancel())`, with `Content-Length` set from
       `metadata()` when it is known (`>= 0`).
     - **Correction (2026-11-21, found while applying R5):** that fix is incomplete. Vert.x's pipe
       registers no handler on the destination (`PipeImpl` constructor and `to()`), so `send`
       fails only when a write fails. While S3 sends data, the next write to the closed
       connection fails, and the cancel then stops the drain. While no data arrives, `send`
       never fails and the stream keeps its S3 connection. Cancel on the response's close
       handler as well: `resp.closeHandler(v -> stream.cancel())`. A stub test that withholds
       the rest of the object proves it, and fails (times out) without the close handler.
     - The manual's example has neither, and uses `pipeTo` where `HttpServerResponse.send(ReadStream)`
       is the Vert.x 5 idiom. Its file example opens the file after getting the stream, which
       forces a cancel when the open fails; opening the file first avoids that. Docs fix (R5).
  2. **Consume or cancel, even on error paths:** the same contract as core's
     `HttpClientResponse`. Parity, not a deviation.
  3. **Paging** needs a recursive `compose`. Acceptable without a helper.
- **Virtual threads, `await()`:** the most natural of the four. Paging is a `do … while` loop;
  `catch (S3ServiceException e)` works because our exceptions are unchecked;
  `ReadStream.blockingStream()` is inherited.
- **Kotlin `coAwait()`** (kotlinc 2.4): `coAwait`, `toReceiveChannel(vertx)` and setter chains
  (`S3ListObjectsOptions().setPrefix(...)`) work. Missing compared with official modules: the
  generated `s3ClientOptionsOf(...)` functions from vertx-lang-kotlin; setter chains are fine, not
  worth adding. Nullability is only right with codegen `@Nullable` (item 2).
- **Mutiny** (no generated wrappers): compiles, but second-class:
  - every call needs `UniHelper.toUni(...)`;
  - Vert.x objects must be unwrapped with `getDelegate()` (`Vertx`, `AsyncFile`,
    `HttpServerResponse`), and the core `Buffer` is mixed with Mutiny's wrapped `Buffer`;
  - cancelling a `Multi` from `MultiHelper.toMulti(stream)` detaches the handlers and **resumes**
    the stream (`vertx-mutiny-runtime/.../MultiReadStream.java:38-48`, `release()`), so it also
    downloads the rest instead of calling `cancel()`. Users must call `cancel()` themselves (R5).
  - With `@VertxGen`, anyone can generate wrappers (`Uni` methods, `…AndAwait`,
    `S3ObjectStream.toMulti()`). Shipping a Mutiny artifact ourselves isn't needed for 1.0.

## 8. Naming, and the manual's structure and tone
- **Method names** are S3 operation names (`putObject`, `headBucket`), as redis uses redis
  command names. Idiomatic.
- **`cancel()`** aborts and closes the connection, like `HttpClientRequest.cancel()` and
  `HttpServerResponse.cancel()`, rather than closing gracefully like `RowStream.close()`. Keep it.
- **`S3ObjectMetadata.getMetadata()`** reads as "metadata of the metadata". Suggest
  `userMetadata` there and in `S3PutObjectOptions`; cheap now, impossible after RC1. **Decided:**
  R6.
- **The manual vs the official ones:**
  - the title matches mail's style ("= Vert.x Mail client");
  - every official manual opens with a Maven/Gradle dependency block; ours has none;
  - "Using it next to the AWS SDK" is its own section; better as a single NOTE;
  - the "differs from the AWS SDK default" addressing note can stay as a short note;
  - the "being rewritten" WARNING goes at RC1;
  - keep "Contexts and threading": official manuals don't have it, but it's our selling point.
- **README:** still titled "Object Storage Client". Gate 3 covers it.

## Recommendations by cost (as proposed)
| # | Change | Cost |
|---|---|---|
| R1 | `@VertxGen` on `S3Client` and `S3ObjectStream`, plus `@CacheReturn`, explicit `@Fluent`, 3× `@Nullable` | Trivial; compiled in the prototype |
| R2 | `S3Exception extends VertxException`; no stack trace on service errors | Small |
| R3 | `create(vertx, options, Supplier<S3Credentials>)`; delete `S3CredentialsProvider` and the options field | Small to medium; API change |
| R4 | Phase 3 presigner: `String` instead of `URI` (or a `@GenIgnore` `URI` variant), expiry in seconds, `Clock` only as `@GenIgnore` or an impl test hook | Design note |
| R5 | Docs: `send(stream)` + `Content-Length` + cancel on failure, open the file first, dependency block, AWS section as a note | Docs |
| R6 | Optional: rename `metadata` to `userMetadata` | Small; API change |

See "Owner decisions" at the top for what was decided.
