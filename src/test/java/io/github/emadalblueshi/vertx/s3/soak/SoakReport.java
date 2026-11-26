package io.github.emadalblueshi.vertx.s3.soak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * What a soak run measured, the verdict, and the report: {@code report.md} (also printed),
 * {@code checkpoints.csv} and {@code samples.csv}.
 */
final class SoakReport {

  private static final double MIB = 1024 * 1024;
  /** Latency buckets: bucket i holds durations up to 2^(i/4) microseconds (about 19% wide). */
  private static final int BUCKETS = 4 * 40;
  private static final int MAX_LISTED = 20;

  final Map<SoakWorkload.Op, OpStats> ops = new EnumMap<>(SoakWorkload.Op.class);
  final List<Sample> samples = new ArrayList<>();
  final List<Sample> checkpoints = new ArrayList<>();
  private final List<String> violations = Collections.synchronizedList(new ArrayList<>());
  private final List<Check> checks = new ArrayList<>();
  /** When the workers started. */
  Instant started = Instant.now();
  Instant ended;
  String setup = "";
  String afterClose = "not measured";

  SoakReport() {
    for (SoakWorkload.Op op : SoakWorkload.Op.values()) {
      ops.put(op, new OpStats());
    }
  }

  /** A violation stops the run: a hard check failed, or an operation had an unexpected outcome. */
  void violation(String what) {
    synchronized (violations) {
      if (violations.size() < MAX_LISTED) {
        violations.add(elapsed(Instant.now()) + " " + what);
      } else if (violations.size() == MAX_LISTED) {
        violations.add("(more violations not listed)");
      }
    }
  }

  boolean violated() {
    return !violations.isEmpty();
  }

  boolean passed() {
    return violations.isEmpty() && checks.stream().allMatch(check -> check.pass);
  }

  long totalOps() {
    return ops.values().stream().mapToLong(OpStats::count).sum();
  }

  /**
   * Judges the trends over the checkpoints after warm-up: the baseline is the median of the first
   * three, the final value the median of the last three, and the slope a least-squares fit, judged
   * only over at least an hour.
   */
  void evaluate(SoakLimits limits, long leakReports) {
    List<Sample> after = checkpoints.stream().filter(sample -> !sample.warmUp).collect(Collectors.toList());
    if (after.size() < 6) {
      checks.add(new Check("checkpoints after warm-up", "", Integer.toString(after.size()), "", ">= 6", false));
      return;
    }
    double hours = (after.get(after.size() - 1).elapsedMillis - after.get(0).elapsedMillis) / 3_600_000.0;
    trend("live heap", after, sample -> sample.heap / MIB, limits.heapGrowthMiB, limits.heapSlopeMiBPerHour, hours);
    trend("Netty direct memory", after, sample -> sample.nettyDirect / MIB, limits.directGrowthMiB,
      limits.directSlopeMiBPerHour, hours);
    trend("JVM direct buffers", after, sample -> sample.jvmDirect / MIB, limits.directGrowthMiB,
      limits.directSlopeMiBPerHour, hours);
    bound("threads", after, sample -> sample.threads, limits.extraThreads);
    bound("open files besides the connections", after, sample -> sample.otherFiles, limits.extraOpenFiles);
    hard("requests waiting for a connection · in flight (max at checkpoints)",
      max(checkpoints, s -> s.queued) + " · " + max(checkpoints, s -> s.inFlight), "0 · 0",
      max(checkpoints, s -> s.queued) + max(checkpoints, s -> s.inFlight) == 0);
    long sockets = Math.max(max(samples, s -> s.established), max(checkpoints, s -> s.established));
    hard("sockets to the server (max) · connections (max at checkpoints)", sockets + " · "
      + max(checkpoints, s -> s.connections), "<= " + limits.maxConnections,
      sockets <= limits.maxConnections && max(checkpoints, s -> s.connections) <= limits.maxConnections);
    hard("CLOSE_WAIT sockets (max at checkpoints) · after close", max(checkpoints, s -> s.closeWait) + " · " + afterClose,
      "0 · no sockets, no connections", max(checkpoints, s -> s.closeWait) == 0 && afterClose.startsWith("none"));
    hard("open multipart uploads · leftover objects (max at checkpoints)",
      max(checkpoints, s -> s.uploads) + " · " + max(checkpoints, s -> s.leftovers), "0 · 0",
      max(checkpoints, s -> s.uploads) + max(checkpoints, s -> s.leftovers) == 0);
    long unexpected = ops.values().stream().mapToLong(stats -> stats.unexpected).sum();
    hard("Netty leak reports · unexpected outcomes", leakReports + " · " + unexpected, "0 · 0",
      leakReports == 0 && unexpected == 0);
  }

  private void trend(String name, List<Sample> after, ToDoubleFunction<Sample> value, double growthLimit,
                     double slopeLimit, double hours) {
    double baseline = median(after.subList(0, 3), value);
    double last = median(after.subList(after.size() - 3, after.size()), value);
    double slope = slope(after, value);
    boolean slopeJudged = hours >= 1;
    boolean pass = last - baseline <= growthLimit && (!slopeJudged || slope <= slopeLimit);
    checks.add(new Check(name, mib(baseline), mib(last), String.format(Locale.ROOT, "%+.1f MiB/h%s", slope,
      slopeJudged ? "" : " (not judged under 1 h)"), String.format(Locale.ROOT, "growth <= %.0f MiB, slope <= %.0f MiB/h",
      growthLimit, slopeLimit), pass));
  }

  private void bound(String name, List<Sample> after, ToDoubleFunction<Sample> value, int extra) {
    double baseline = median(after.subList(0, 3), value);
    double highest = after.stream().mapToDouble(value).max().orElse(0);
    checks.add(new Check(name, String.format(Locale.ROOT, "%.0f", baseline), String.format(Locale.ROOT, "%.0f (max)", highest),
      "", "<= baseline + " + extra, highest <= baseline + extra));
  }

  private void hard(String name, String measured, String limit, boolean pass) {
    checks.add(new Check(name, "", measured, "", limit, pass));
  }

  /** The median of the values after warm-up: the first three give the baseline. */
  static double median(List<Sample> samples, ToDoubleFunction<Sample> value) {
    double[] sorted = samples.stream().mapToDouble(value).sorted().toArray();
    return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
  }

  /** Least-squares slope of {@code value} per hour. */
  static double slope(List<Sample> samples, ToDoubleFunction<Sample> value) {
    int n = samples.size();
    double meanX = samples.stream().mapToDouble(s -> s.elapsedMillis / 3_600_000.0).average().orElse(0);
    double meanY = samples.stream().mapToDouble(value).average().orElse(0);
    double numerator = 0;
    double denominator = 0;
    for (Sample sample : samples) {
      double dx = sample.elapsedMillis / 3_600_000.0 - meanX;
      numerator += dx * (value.applyAsDouble(sample) - meanY);
      denominator += dx * dx;
    }
    return n < 2 || denominator == 0 ? 0 : numerator / denominator;
  }

  private static long max(List<Sample> samples, java.util.function.ToLongFunction<Sample> value) {
    return samples.stream().mapToLong(value).max().orElse(0);
  }

  void write(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("report.md"), markdown(), StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("checkpoints.csv"), csv(checkpoints), StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("samples.csv"), csv(samples), StandardCharsets.UTF_8);
  }

  String markdown() {
    StringBuilder out = new StringBuilder();
    out.append("# Soak report: ").append(passed() ? "PASS" : "FAIL").append("\n\n").append(setup)
      .append("Started ").append(started).append(", ended ").append(ended).append(" (")
      .append(elapsed(ended == null ? Instant.now() : ended)).append("), ").append(totalOps()).append(" operations\n\n");
    out.append("| Operation | Count | Unexpected | p50 | p99 | Max | MiB moved |\n|---|---|---|---|---|---|---|\n");
    ops.forEach((op, stats) -> out.append(stats.row(op.label)));
    out.append("\n| Check | Baseline | Final | Slope | Limit | Result |\n|---|---|---|---|---|---|\n");
    for (Check check : checks) {
      out.append("| ").append(check.name).append(" | ").append(check.baseline).append(" | ").append(check.last)
        .append(" | ").append(check.slope).append(" | ").append(check.limit).append(" | ")
        .append(check.pass ? "pass" : "**FAIL**").append(" |\n");
    }
    if (!violations.isEmpty()) {
      out.append("\nViolations (the run stopped at the first):\n\n");
      synchronized (violations) {
        violations.forEach(v -> out.append("- ").append(v).append('\n'));
      }
    }
    out.append("\n| # | Time | Phase | Heap MiB | Netty direct MiB | JVM direct MiB | RSS MiB | Threads | Other files"
      + " | Connections | Sockets | Waiting/in flight | Uploads | Leftovers | Operations |\n"
      + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (int i = 0; i < checkpoints.size(); i++) {
      Sample c = checkpoints.get(i);
      out.append(String.format(Locale.ROOT, "| %d | %s | %s | %.1f | %.1f | %.1f | %.0f | %d | %d | %d | %d (+%d CLOSE_WAIT) | %d/%d | %d | %d | %d |%n",
        i + 1, duration(c.elapsedMillis), c.warmUp ? "warm-up" : "", c.heap / MIB, c.nettyDirect / MIB, c.jvmDirect / MIB,
        c.resident / MIB, c.threads, c.otherFiles, c.connections, c.established, c.closeWait, c.queued, c.inFlight,
        c.uploads, c.leftovers, c.ops));
    }
    return out.toString();
  }

  private static String csv(List<Sample> rows) {
    StringBuilder out = new StringBuilder("elapsed_s,phase,operations,heap_bytes,netty_direct_bytes,jvm_direct_bytes,"
      + "resident_bytes,threads,open_files,other_files,connections,queued,in_flight,established,close_wait,uploads,leftovers\n");
    for (Sample s : rows) {
      out.append(s.elapsedMillis / 1000).append(',').append(s.warmUp ? "warm-up" : "load").append(',').append(s.ops)
        .append(',').append(s.heap).append(',').append(s.nettyDirect).append(',').append(s.jvmDirect).append(',')
        .append(s.resident).append(',').append(s.threads).append(',').append(s.openFiles).append(',').append(s.otherFiles)
        .append(',').append(s.connections).append(',').append(s.queued).append(',').append(s.inFlight)
        .append(',').append(s.established).append(',').append(s.closeWait).append(',').append(s.uploads).append(',')
        .append(s.leftovers).append('\n');
    }
    return out.toString();
  }

  private String elapsed(Instant at) {
    return duration(Duration.between(started, at).toMillis());
  }

  static String duration(long millis) {
    long seconds = millis / 1000;
    return String.format(Locale.ROOT, "%dh%02dm%02ds", seconds / 3600, seconds / 60 % 60, seconds % 60);
  }

  private static String mib(double value) {
    return String.format(Locale.ROOT, "%.1f MiB", value);
  }

  /** One measurement: every 30 s under load, or at a checkpoint once the workers are idle. */
  static final class Sample {
    long elapsedMillis;
    boolean warmUp;
    long ops;
    long heap;
    long nettyDirect;
    long jvmDirect;
    long resident;
    int threads;
    long openFiles;
    /** Open files other than the sockets to the server, whose number follows the pools. */
    long otherFiles;
    long connections;
    long queued;
    long inFlight;
    int established;
    int closeWait;
    long uploads;
    long leftovers;
  }

  /** Counts, outcomes, bytes and latencies of one operation, recorded from the workers' event loops. */
  static final class OpStats {
    private long count;
    private long unexpected;
    private long bytes;
    private long maxNanos;
    private final long[] latencies = new long[BUCKETS];

    synchronized void record(long nanos, long moved, boolean expected) {
      count++;
      if (!expected) {
        unexpected++;
      }
      bytes += Math.max(0, moved);
      maxNanos = Math.max(maxNanos, nanos);
      double micros = Math.max(1, nanos / 1000.0);
      latencies[(int) Math.min(BUCKETS - 1, Math.ceil(4 * Math.log(micros) / Math.log(2)))]++;
    }

    synchronized long count() {
      return count;
    }

    /** The upper bound of the bucket holding the {@code p}th percentile (at most the maximum), in milliseconds. */
    private synchronized double percentileMillis(double p) {
      long rank = (long) Math.ceil(p * count);
      long seen = 0;
      for (int i = 0; i < BUCKETS; i++) {
        seen += latencies[i];
        if (seen >= rank && seen > 0) {
          return Math.min(Math.pow(2, i / 4.0) / 1000, maxNanos / 1e6);
        }
      }
      return 0;
    }

    synchronized String row(String label) {
      return String.format(Locale.ROOT, "| %s | %d | %d | %s | %s | %s | %.0f |%n", label, count, unexpected,
        millis(percentileMillis(0.5)), millis(percentileMillis(0.99)), millis(maxNanos / 1e6), bytes / MIB);
    }

    private static String millis(double value) {
      return value < 10 ? String.format(Locale.ROOT, "%.1f ms", value) : String.format(Locale.ROOT, "%.0f ms", value);
    }
  }

  private static final class Check {
    final String name;
    final String baseline;
    final String last;
    final String slope;
    final String limit;
    final boolean pass;

    Check(String name, String baseline, String last, String slope, String limit, boolean pass) {
      this.name = name;
      this.baseline = baseline;
      this.last = last;
      this.slope = slope;
      this.limit = limit;
      this.pass = pass;
    }
  }
}
