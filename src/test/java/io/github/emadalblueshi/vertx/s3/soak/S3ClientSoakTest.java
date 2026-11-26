package io.github.emadalblueshi.vertx.s3.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.emadalblueshi.vertx.s3.NettyLeakCheck;
import io.github.emadalblueshi.vertx.s3.NettyLeaks;
import io.github.emadalblueshi.vertx.s3.S3Client;
import io.github.emadalblueshi.vertx.s3.S3ClientOptions;
import io.github.emadalblueshi.vertx.s3.S3Credentials;
import io.github.emadalblueshi.vertx.s3.S3ListObjectsOptions;
import io.github.emadalblueshi.vertx.s3.backend.S3TestBackend;
import io.github.emadalblueshi.vertx.s3.backend.SiloContainer;
import io.github.emadalblueshi.vertx.s3.backend.TestResources;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.metrics.MetricsOptions;

/**
 * Release gate 5, the soak: a mixed workload against a dedicated Silo over HTTPS for
 * {@code S3_SOAK_DURATION} (e.g. {@code 8h}, {@code 15m}). Eight workers share one client, as an
 * application does. Every 30 s a sample is taken under load; at each checkpoint the workers finish
 * their operations, two garbage collections run, Netty reports any leak, and memory, threads, open
 * files, connections, sockets, multipart uploads and leftover objects are measured. A hard
 * violation stops the run at once; the trends are judged at the end. The report goes to
 * {@code target/soak}.
 * <p>
 * The test JVM must clear soft references at every garbage collection
 * ({@code -XX:SoftRefLRUPolicyMSPerMB=0}, through {@code -DargLine}): otherwise the JDK's softly
 * referenced caches count as live heap. Its TLS client keeps every session that resumes from a
 * cached one reachable from it, so with a large heap the live heap grows with every new connection,
 * for hours, although the garbage collector would reclaim it all under memory pressure.
 * <p>
 * On demand only: skipped unless {@code S3_SOAK_DURATION} is set, and never on CI.
 */
@ExtendWith(NettyLeakCheck.class)
@EnabledIfEnvironmentVariable(named = "S3_SOAK_DURATION", matches = ".+")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class S3ClientSoakTest {

  private static final int WORKERS = 8;
  private static final int MAX_PAUSE_MILLIS = 20;
  private static final Duration SAMPLE_PERIOD = Duration.ofSeconds(30);
  /** A safety net: the operations in flight finish in seconds. */
  private static final Duration QUIESCE_TIMEOUT = Duration.ofMinutes(2);
  /** How long the pools may take to see a request's end, and a closed socket to leave CLOSE_WAIT. */
  private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(5);
  /** Room for the fixtures, the uploads in flight, and up to 5 s of deleted objects' data. */
  private static final int DATA_MIB = 4096;
  private static final Path REPORT_DIR = Path.of("target", "soak");

  private final SoakLimits limits = new SoakLimits();
  private final SoakReport report = new SoakReport();
  private final SoakMetrics metrics = new SoakMetrics();
  private final List<Worker> workers = new ArrayList<>();
  private final Set<Worker> idle = ConcurrentHashMap.newKeySet();
  private volatile boolean pausing;
  /**
   * Set when the workers are paused for good: a checkpoint found a violation (it was the last), or
   * they didn't finish their operations (the final checkpoint doesn't wait for them again).
   */
  private boolean stopped;
  private Vertx vertx;
  private S3Client client;
  private SoakWorkload workload;
  private SoakProbe probe;
  private String bucket;
  private long started;
  private long warmUpNanos;
  private long leakReports;

  @Test
  void soak() throws Exception {
    assertEquals("0", SoakProbe.vmOption("SoftRefLRUPolicyMSPerMB"), "run the soak with "
      + "-DargLine=-XX:SoftRefLRUPolicyMSPerMB=0 (see AGENTS.md, \"Commands\"), so that the JDK's softly referenced "
      + "caches don't count as live heap");
    Duration duration = parse(System.getenv("S3_SOAK_DURATION"));
    Duration interval = clamp(duration.dividedBy(48), Duration.ofMinutes(1), Duration.ofMinutes(10));
    Duration warmUp = clamp(duration.dividedBy(16), Duration.ofMinutes(2), Duration.ofMinutes(30));
    S3TestBackend backend = S3TestBackend.siloTlsInMemory(DATA_MIB);
    long starting = System.nanoTime();
    backend.start();
    Duration startUp = Duration.ofNanos(System.nanoTime() - starting);
    Path files = Files.createTempDirectory("vertx-s3-soak-");
    boolean closed = false;
    try {
      vertx = Vertx.builder().with(new VertxOptions().setMetricsOptions(new MetricsOptions().setEnabled(true)))
        .withMetrics(metrics).build();
      S3ClientOptions options = TestResources.clientOptions(backend);
      options.getPoolOptions().setHttp1MaxSize(16);
      client = S3Client.create(vertx, options);
      S3ClientOptions wrong = TestResources.clientOptions(backend)
        .setCredentials(new S3Credentials(backend.accessKey(), "wrong-" + backend.secretKey()));
      wrong.getPoolOptions().setHttp1MaxSize(2);
      S3Client wrongSecret = S3Client.create(vertx, wrong);
      S3ClientOptions small = TestResources.clientOptions(backend).setMaxBufferedObjectSize(512);
      small.getPoolOptions().setHttp1MaxSize(2);
      S3Client capped = S3Client.create(vertx, small);
      HttpClient http = vertx.createHttpClient(backend.httpClientOptions(), new PoolOptions().setHttp1MaxSize(8));
      bucket = TestResources.uniqueBucketName();
      await(client.createBucket(bucket));
      workload = new SoakWorkload(vertx, client, wrongSecret, capped, http, bucket);
      workload.prepare(files);
      probe = new SoakProbe(backend.endpoint().getPort());
      report.setup = setup(duration, interval, warmUp, startUp);
      run(duration, interval, warmUp);
      await(Future.join(client.close(), wrongSecret.close(), capped.close(), http.close()));
      closed = true;
      await(vertx.close());
      afterClose();
    } finally {
      if (!closed && vertx != null) {
        vertx.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.MINUTES);
      }
      report.ended = Instant.now();
      report.evaluate(limits, leakReports);
      report.write(REPORT_DIR);
      System.out.println(report.markdown());
      backend.stop();
      deleteRecursively(files);
    }
    assertTrue(report.passed(), "the soak failed; see " + REPORT_DIR.resolve("report.md"));
  }

  /** Runs the workers until the end or the first violation, sampling and checkpointing on the way. */
  private void run(Duration duration, Duration interval, Duration warmUp) throws Exception {
    started = System.nanoTime();
    report.started = Instant.now();
    warmUpNanos = warmUp.toNanos();
    long end = started + duration.toNanos();
    long nextSample = started + SAMPLE_PERIOD.toNanos();
    long nextCheckpoint = started + interval.toNanos();
    for (int i = 0; i < WORKERS; i++) {
      Worker worker = new Worker(i);
      await(vertx.deployVerticle(worker));
      workers.add(worker);
    }
    workers.forEach(Worker::go);
    while (!report.violated() && System.nanoTime() < end) {
      long now = System.nanoTime();
      if (now >= nextCheckpoint) {
        checkpoint(true);
        nextCheckpoint += interval.toNanos();
      } else if (now >= nextSample) {
        sample();
        nextSample += SAMPLE_PERIOD.toNanos();
      } else {
        Thread.sleep(Math.min(1000, TimeUnit.NANOSECONDS.toMillis(Math.min(nextSample, nextCheckpoint) - now) + 1));
      }
    }
    if (!stopped) {
      checkpoint(false);
    }
  }

  /**
   * Under load, the sockets are what's checked: a socket leaves ESTABLISHED when it's closed, before
   * the pool frees its slot, whereas Vert.x may count a closing connection next to its replacement.
   */
  private void sample() {
    SoakReport.Sample sample = measure();
    report.samples.add(sample);
    if (sample.established > limits.maxConnections) {
      report.violation("more sockets to the server than the pools hold: " + sample.established + " (limit "
        + limits.maxConnections + ")");
    }
  }

  /**
   * Lets the workers finish their operations, runs Netty's leak check (two confirmed garbage
   * collections first), measures, and applies the hard checks; then resumes the workers, unless
   * {@code resume} is false or there was a violation.
   */
  private void checkpoint(boolean resume) throws Exception {
    if (!quiesce()) {
      stopped = true;
      report.violation("the workers didn't finish their operations within " + QUIESCE_TIMEOUT.toMinutes() + " minutes");
      return;
    }
    try {
      String leaks = NettyLeaks.collect();
      if (!leaks.isEmpty()) {
        leakReports += leaks.split("LEAK: ", -1).length - 1;
        report.violation("Netty reported leaked buffers:\n" + leaks);
      }
      settle();
      SoakReport.Sample checkpoint = measure();
      checkpoint.uploads = await(client.listMultipartUploads(bucket, null)).size();
      checkpoint.leftovers = await(client.listObjects(bucket, new S3ListObjectsOptions().setPrefix(SoakWorkload.WORKER_PREFIX)))
        .getObjects().size();
      report.checkpoints.add(checkpoint);
      hardChecks(checkpoint);
    } finally {
      if (resume && !report.violated()) {
        resumeWorkers();
      } else {
        stopped = true;
      }
    }
  }

  private void hardChecks(SoakReport.Sample c) {
    if (c.queued != 0 || c.inFlight != 0) {
      report.violation("idle, yet " + c.queued + " requests waiting for a connection and " + c.inFlight + " in flight");
    }
    if (c.closeWait != 0) {
      report.violation(c.closeWait + " sockets stuck in CLOSE_WAIT");
    }
    if (c.connections > limits.maxConnections || c.established > limits.maxConnections) {
      report.violation(c.connections + " connections, " + c.established + " sockets (limit " + limits.maxConnections + ")");
    }
    if (c.uploads != 0 || c.leftovers != 0) {
      report.violation("idle, yet " + c.uploads + " multipart uploads open and " + c.leftovers + " objects left");
    }
    List<SoakReport.Sample> after = new ArrayList<>();
    for (SoakReport.Sample sample : report.checkpoints) {
      if (!sample.warmUp) {
        after.add(sample);
      }
    }
    if (after.size() > 3) {
      double baseline = SoakReport.median(after.subList(0, 3), sample -> sample.heap);
      double growthMiB = (c.heap - baseline) / (1024 * 1024);
      if (growthMiB > 2 * limits.heapGrowthMiB) {
        report.violation(String.format(Locale.ROOT, "the live heap grew %.1f MiB from its baseline", growthMiB));
      }
    }
  }

  /** Waits briefly for the pools to see the last requests end, and for closed sockets to leave CLOSE_WAIT. */
  private void settle() throws InterruptedException {
    long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline && (metrics.queued.get() != 0 || metrics.inFlight.get() != 0
      || probe.sockets().closeWait != 0)) {
      Thread.sleep(50);
    }
  }

  private SoakReport.Sample measure() {
    SoakReport.Sample sample = new SoakReport.Sample();
    long elapsed = System.nanoTime() - started;
    sample.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsed);
    sample.warmUp = elapsed < warmUpNanos;
    sample.ops = report.totalOps();
    sample.heap = probe.heapUsed();
    sample.nettyDirect = probe.nettyDirectUsed();
    sample.jvmDirect = probe.jvmDirectUsed();
    sample.resident = probe.residentBytes();
    sample.threads = probe.threads();
    sample.openFiles = probe.openFiles();
    sample.connections = metrics.connections.get();
    sample.queued = metrics.queued.get();
    sample.inFlight = metrics.inFlight.get();
    SoakProbe.Sockets sockets = probe.sockets();
    sample.established = sockets.established;
    sample.closeWait = sockets.closeWait;
    sample.otherFiles = sample.openFiles - sockets.established - sockets.closeWait;
    return sample;
  }

  /** Once the clients and Vert.x are closed, no socket to the server and no connection may remain. */
  private void afterClose() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    SoakProbe.Sockets sockets = probe.sockets();
    while (System.nanoTime() < deadline && (sockets.established + sockets.closeWait > 0 || metrics.connections.get() > 0)) {
      Thread.sleep(50);
      sockets = probe.sockets();
    }
    if (sockets.established + sockets.closeWait > 0 || metrics.connections.get() > 0) {
      report.afterClose = sockets.established + " sockets established, " + sockets.closeWait + " in CLOSE_WAIT, "
        + metrics.connections.get() + " connections";
      report.violation("after closing: " + report.afterClose);
    } else {
      report.afterClose = "none";
    }
  }

  private boolean quiesce() throws InterruptedException {
    pausing = true;
    long deadline = System.nanoTime() + QUIESCE_TIMEOUT.toNanos();
    synchronized (idle) {
      while (idle.size() < workers.size()) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return false;
        }
        idle.wait(TimeUnit.NANOSECONDS.toMillis(left) + 1);
      }
    }
    return true;
  }

  private void resumeWorkers() {
    pausing = false;
    synchronized (idle) {
      idle.forEach(Worker::go);
      idle.clear();
    }
  }

  private String setup(Duration duration, Duration interval, Duration warmUp, Duration startUp)
    throws ReflectiveOperationException, IOException {
    Object versions = Class.forName("io.netty.util.Version").getMethod("identify").invoke(null);
    Object nettyCommon = ((Map<?, ?>) versions).get("netty-common");
    String netty = nettyCommon == null ? "?" : (String) nettyCommon.getClass().getMethod("artifactVersion").invoke(nettyCommon);
    Object level = Class.forName("io.netty.util.ResourceLeakDetector").getMethod("getLevel").invoke(null);
    String vertxVersion;
    try (InputStream in = Vertx.class.getResourceAsStream("/META-INF/vertx/vertx-version.txt")) {
      vertxVersion = in == null ? "?" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    return "Silo " + SiloContainer.IMAGE.getVersionPart() + " over HTTPS, data in memory, ready after "
      + SoakReport.duration(startUp.toMillis()) + " (its first purge of deleted data) · Vert.x "
      + vertxVersion + " · Netty " + netty + " · "
      + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version") + "\n\n"
      + WORKERS + " workers, pausing 0–" + MAX_PAUSE_MILLIS + " ms · pools 16 + 8 + 2 + 2 · Netty leak detection: "
      + level + " · soft references cleared at every GC · max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
      + " MiB\n\nPlanned " + SoakReport.duration(duration.toMillis()) + ", a checkpoint every "
      + SoakReport.duration(interval.toMillis()) + ", warm-up " + SoakReport.duration(warmUp.toMillis()) + "\n\n";
  }

  /** {@code 8h}, {@code 15m}, {@code 90s}, {@code 1h30m}. */
  static Duration parse(String text) {
    Matcher matcher = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matcher(text.trim());
    Duration duration = matcher.matches()
      ? Duration.ofHours(number(matcher.group(1))).plusMinutes(number(matcher.group(2))).plusSeconds(number(matcher.group(3)))
      : Duration.ZERO;
    if (duration.isZero()) {
      throw new IllegalArgumentException("S3_SOAK_DURATION must be like 8h, 15m, 90s or 1h30m, not '" + text + "'");
    }
    return duration;
  }

  private static long number(String digits) {
    return digits == null ? 0 : Long.parseLong(digits);
  }

  private static Duration clamp(Duration value, Duration min, Duration max) {
    return value.compareTo(min) < 0 ? min : value.compareTo(max) > 0 ? max : value;
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.MINUTES);
  }

  private static void deleteRecursively(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static String describe(Throwable error) {
    StringBuilder text = new StringBuilder(String.valueOf(error));
    for (Throwable cause = error.getCause(); cause != null && text.length() < 2000; cause = cause.getCause()) {
      text.append(" <- ").append(cause);
    }
    return text.toString();
  }

  /**
   * One worker: a verticle, so each has its own event loop, running one operation at a time with a
   * short pause between them.
   */
  private final class Worker extends VerticleBase {
    private final int id;
    private final Random dice;
    private long sequence;

    Worker(int id) {
      this.id = id;
      this.dice = new Random(id);
    }

    /** Starts (or resumes) the operations, on the worker's context. */
    void go() {
      context.runOnContext(v -> next());
    }

    private void next() {
      if (pausing) {
        synchronized (idle) {
          idle.add(this);
          idle.notifyAll();
        }
        return;
      }
      SoakWorkload.Op op = workload.pick(dice);
      String key = SoakWorkload.WORKER_PREFIX + id + "/" + sequence++;
      long begin = System.nanoTime();
      Future<Long> outcome;
      try {
        outcome = workload.run(op, key, dice);
      } catch (RuntimeException e) {
        outcome = Future.failedFuture(e);
      }
      outcome.onComplete(result -> {
        report.ops.get(op).record(System.nanoTime() - begin, result.succeeded() ? result.result() : 0, result.succeeded());
        if (result.failed()) {
          report.violation(op.label + " (" + key + "): " + describe(result.cause()));
        }
        int pause = dice.nextInt(MAX_PAUSE_MILLIS + 1);
        if (pause == 0) {
          context.runOnContext(v -> next());
        } else {
          vertx.setTimer(pause, timer -> next());
        }
      });
    }
  }
}
