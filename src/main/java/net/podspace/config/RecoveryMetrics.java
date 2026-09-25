package net.podspace.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.podspace.pipeline.EngineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Measures how long each pipeline engine spends unable to work.
 *
 * <p>Detecting an outage was never the hard part - the failure counters already did that. What was
 * missing is how long recovery took, which is the figure a broker upgrade, a failover drill or an
 * infrastructure change is actually judged on. Without it the tool can say "a broker died and
 * nothing was lost" but not "the pipeline was stalled for 12 seconds", and the second statement is
 * the one that decides whether a maintenance window is acceptable.
 *
 * <p>An outage is timed from the <em>first</em> failure to the success that ends the run, so it
 * spans every retry and backoff in between rather than just the last attempt. The clock is
 * nanotime, so a wall-clock adjustment mid-outage cannot distort it.
 *
 * <p>Two views, because they answer different questions:
 * <ul>
 *   <li>{@code kproducer.engine.outage} - a Timer, one sample per completed outage. Gives count,
 *       total stalled time and a distribution. Only recorded once recovery happens.</li>
 *   <li>{@code kproducer.engine.outage.current} - a Gauge, seconds the engine has been failing
 *       right now, zero while healthy. An outage that never ends produces no Timer sample at all,
 *       so without this a total failure would look indistinguishable from perfect health.</li>
 * </ul>
 */
public final class RecoveryMetrics {
    private static final Logger logger = LoggerFactory.getLogger(RecoveryMetrics.class);

    /** SLOs chosen around what a Kafka failover realistically takes: leader election is seconds. */
    private static final Duration[] OUTAGE_BUCKETS = {
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15)
    };

    private RecoveryMetrics() {
    }

    /**
     * Wires one engine's loop to the registry. The {@code engine} tag keeps publisher, consumer and
     * relay separate - they fail for different reasons and recover at different speeds, and a
     * single merged series would hide that.
     */
    public static void bind(MeterRegistry registry, String engine, EngineStatus status) {
        Timer outage = Timer.builder("kproducer.engine.outage")
                .description("Time an engine spent failing, from first failure to recovery")
                .tag("engine", engine)
                .publishPercentiles(0.5, 0.95)
                .serviceLevelObjectives(OUTAGE_BUCKETS)
                .register(registry);

        Gauge.builder("kproducer.engine.outage.current", status,
                        s -> s.getCurrentOutage().toMillis() / 1000.0)
                .description("Seconds the engine has currently been failing; 0 while healthy")
                .tag("engine", engine)
                .baseUnit("seconds")
                .register(registry);

        status.onRecovery((name, duration, failures) -> {
            outage.record(duration);
            logger.info("{} recovered after {} ms and {} failed attempts",
                    name, duration.toMillis(), failures);
        });
    }
}
