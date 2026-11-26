package io.github.emadalblueshi.vertx.s3.soak;

/**
 * The soak's thresholds. Trends are judged over the checkpoints after warm-up, against the baseline
 * (the median of the first three); the slope only over at least an hour.
 */
final class SoakLimits {

  /** The live heap may grow this much from the baseline, and twice as much stops the run. */
  final double heapGrowthMiB = 32;
  final double heapSlopeMiBPerHour = 4;
  /** Applies to Netty's direct memory and to the JVM's direct buffers. */
  final double directGrowthMiB = 32;
  final double directSlopeMiBPerHour = 4;
  final int extraThreads = 2;
  /** Open files other than the sockets to the server, whose number follows the pools. */
  final int extraOpenFiles = 8;
  /** The pools' sizes added up: the S3 client 16, the presigned requests' client 8, the two error clients 2 each. */
  final int maxConnections = 16 + 8 + 2 + 2;
}
