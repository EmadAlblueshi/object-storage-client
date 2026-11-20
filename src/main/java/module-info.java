/**
 * Vert.x S3 client: an asynchronous client for Amazon S3 and S3-compatible storage.
 * <p>
 * Only the API package is exported. Implementation packages ({@code ...vertx.s3.impl})
 * are internal.
 */
module io.github.emadalblueshi.vertx.s3 {
  requires transitive io.vertx.core;

  // Annotations only (provided scope): codegen data objects / JSON converters and docgen.
  requires static io.vertx.codegen.api;
  requires static io.vertx.codegen.json;
  requires static io.vertx.docgen;

  exports io.github.emadalblueshi.vertx.s3;
}
