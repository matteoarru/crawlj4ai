package io.crawlj4ai.scheduler;

import java.time.Duration;

/**
 * Bounds and Poisson sensitivity parameters governing adaptive refresh intervals.
 */
public record SchedulerPolicy(
        Duration minInterval,
        Duration maxInterval,
        Duration initialInterval,
        double emaAlpha,
        double targetChangeProbability,
        double jitterFactor
) {
    public SchedulerPolicy {
        if (minInterval == null || maxInterval == null || initialInterval == null) {
            throw new IllegalArgumentException("Intervals must not be null");
        }
        if (minInterval.isNegative() || minInterval.isZero() || maxInterval.compareTo(minInterval) < 0) {
            throw new IllegalArgumentException("Require 0 < minInterval <= maxInterval");
        }
        if (emaAlpha <= 0.0 || emaAlpha > 1.0) {
            throw new IllegalArgumentException("emaAlpha must be in (0.0, 1.0]");
        }
        if (targetChangeProbability <= 0.05 || targetChangeProbability >= 0.99) {
            throw new IllegalArgumentException("targetChangeProbability must be in (0.05, 0.99)");
        }
        if (jitterFactor < 0.0 || jitterFactor > 0.5) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 0.5]");
        }
    }

    public static SchedulerPolicy defaults() {
        return new SchedulerPolicy(
                Duration.ofMinutes(5),
                Duration.ofDays(7),
                Duration.ofHours(1),
                0.30,
                0.50,
                0.10
        );
    }

    public static SchedulerPolicy volatileTarget() {
        return new SchedulerPolicy(
                Duration.ofMinutes(1),
                Duration.ofHours(12),
                Duration.ofMinutes(10),
                0.40,
                0.35,
                0.10
        );
    }
}
