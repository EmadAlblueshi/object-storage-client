package io.github.emadalblueshi.vertx.s3.backend;

import java.util.function.Supplier;

/**
 * Lazily started, JVM-wide backends for {@link S3TestBackend#sharedSilo()} and
 * {@link S3TestBackend#sharedSiloTls()}. Each starts on first use; Testcontainers' Ryuk removes
 * the containers when the test JVM exits.
 */
final class SharedBackends {

  static final Supplier<S3TestBackend> SILO = new Lazy(S3TestBackend::silo);
  static final Supplier<S3TestBackend> SILO_TLS = new Lazy(S3TestBackend::siloTls);

  /** One Ceph container serves both: its HTTP and HTTPS ports. */
  static final Supplier<S3TestBackend> CEPH = () -> new CephBackend(ceph(), false);
  static final Supplier<S3TestBackend> CEPH_TLS = () -> new CephBackend(ceph(), true);

  private static CephContainer ceph;

  private static synchronized CephContainer ceph() {
    if (ceph == null) {
      CephContainer container = new CephContainer();
      container.start();
      ceph = container;
    }
    return ceph;
  }

  private SharedBackends() {
  }

  private static final class Lazy implements Supplier<S3TestBackend> {
    private final Supplier<S3TestBackend> factory;
    private S3TestBackend started;

    Lazy(Supplier<S3TestBackend> factory) {
      this.factory = factory;
    }

    @Override
    public synchronized S3TestBackend get() {
      if (started == null) {
        S3TestBackend backend = factory.get();
        backend.start();
        started = backend;
      }
      return started;
    }
  }
}
