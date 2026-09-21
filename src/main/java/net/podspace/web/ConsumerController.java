package net.podspace.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import net.podspace.domain.Temperature;
import net.podspace.pipeline.DeliveryLedger;
import net.podspace.pipeline.ValueEnvelope;
import net.podspace.pipeline.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/consumer")
public class ConsumerController {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerController.class);
    /**
     * Explicit buckets, so /actuator/prometheus exports a bounded, readable set of `_bucket`
     * series rather than the ~276 a percentile histogram would generate.
     */
    private static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10),
            Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofSeconds(5), Duration.ofSeconds(30)
    };
    /**
     * Only enough recent samples to eyeball individual messages on /stats. The distribution itself
     * lives in the Timer, so this no longer has to retain everything ever consumed.
     */
    private static final int RECENT_SAMPLES = 1_000;

    private final Watcher<Temperature> watcher;
    private final DeliveryLedger ledger;
    private final Timer latency;
    private final Counter skipped;
    /** Guarded by {@link #recentLock}; oldest entries evicted past RECENT_SAMPLES. */
    private final Deque<ItemStat> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    public ConsumerController(Watcher<Temperature> watcher, MeterRegistry registry,
                              DeliveryLedger ledger) {
        this.watcher = watcher;
        this.ledger = ledger;
        this.latency = Timer.builder("kproducer.message.latency")
                .description("End-to-end latency from message creation to consumption")
                // The role tag that keeps one-way and round-trip samples apart is applied
                // globally as a common tag - see MetricsConfig.
                .publishPercentiles(0.5, 0.95, 0.99)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry);
        this.skipped = Counter.builder("kproducer.message.skipped")
                .description("Consumed messages with no usable timestamp, so no latency recorded")
                .register(registry);
        // Record as each message arrives, on the watcher thread, rather than buffering whole
        // messages until someone calls /stats.
        this.watcher.setSink(this::record);
    }

    @GetMapping("/start")
    public String startConsumer() {
        logger.info("Calling watcher initiate.");
        watcher.initiate();
        logger.info("Woot... started.");
        return "Success... started";
    }

    @GetMapping("/stop")
    public String stopWatcher() {
        try {
            logger.info("Calling watcher quit.");
            watcher.quit();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... stopped.");
        return "Success... stopped";
    }

    @GetMapping("/pause")
    public String pauseWatcher() {
        try {
            logger.info("Calling watcher pause.");
            watcher.pause();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... paused.");
        return "Success... paused";
    }

    @GetMapping("/resume")
    public String resumeWatcher() {
        try {
            logger.info("Calling watcher resume.");
            watcher.resume();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... resumed.");
        return "Success... resumed";
    }

    /**
     * Sink for the watcher: derive end-to-end latency and record it. Runs on the watcher thread,
     * so it stays cheap and swallows nothing silently.
     */
    private void record(ValueEnvelope<Temperature> envelope) {
        Temperature t = envelope.item();
        if (t != null) {
            // Reconcile first and unconditionally: a message with an unusable timestamp still
            // arrived, and must not be reported as lost just because its latency is unknown.
            ledger.received(t.getRun(), t.getSeq());
        }
        // Jackson leaves these null for anything on the topic that is not one of our messages.
        if (t == null || t.getTime() == null || envelope.time() == null) {
            skipped.increment();
            logger.info("Skipping message with no usable timestamp.");
            return;
        }
        // Latency is only meaningful for messages this run produced. A message left on the topic
        // by an earlier run carries that run's timestamp, so subtracting it measures how long the
        // message sat on the topic - hours or days - rather than anything about the pipeline. The
        // reconciliation above already excludes these by run id and counts them as foreign; the
        // timer used not to, which let one backlog replay poison the latency metrics for the rest
        // of the process lifetime, because a timer's histogram is cumulative.
        //
        // This is also what makes autoOffsetReset=earliest usable: the backlog is still counted
        // for delivery reconciliation, but no longer drags the latency distribution with it.
        if (!ledger.getRunId().equals(t.getRun())) {
            return;
        }
        try {
            Instant readTime = Instant.parse(envelope.time());
            Instant writeTime = Instant.parse(t.getTime());
            Duration d = Duration.between(writeTime, readTime);
            latency.record(d);

            ItemStat stat = new ItemStat();
            stat.id = t.getTimeId();
            stat.millis = d.toNanos() / 1_000_000.0;
            stat.size = envelope.size();
            synchronized (recentLock) {
                recent.addLast(stat);
                while (recent.size() > RECENT_SAMPLES) {
                    recent.pollFirst();
                }
            }
        } catch (DateTimeParseException e) {
            skipped.increment();
            logger.info("Skipping message with unparsable timestamp: {}", t.getTime());
        }
    }

    /** The most recent individual messages. The full distribution is on /consumer/histogram. */
    @GetMapping("/stats")
    public String statistics() {
        List<ItemStat> samples;
        synchronized (recentLock) {
            samples = new ArrayList<>(recent);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>time id</th><th>time difference (ms)</th><th>size</th></tr>");
        for (ItemStat t : samples) {
            sb.append("<tr><td>").append(t.id)
                    .append("</td><td>").append(String.format("%.3f", t.millis))
                    .append("</td><td>").append(t.size)
                    .append("</td></tr>\n");
        }
        sb.append("</table><p>showing most recent ").append(samples.size())
                .append(" of ").append(latency.count()).append(" total messages");
        return sb.toString();
    }

    /**
     * Delivery reconciliation: what was published against what came back.
     *
     * <p>{@code pending} is the honest "not yet judged" bucket - sequences that may still be in
     * flight. Stop the publisher, let the pipeline drain, then call with {@code ?finalize=true} to
     * settle them, at which point {@code missing} is the loss figure.
     */
    @GetMapping("/reconciliation")
    public String reconciliation(@RequestParam(defaultValue = "false") boolean finalize) {
        if (finalize) {
            ledger.finalizeOutstanding();
        }
        DeliveryLedger.Snapshot s = ledger.snapshot();
        String verdict = s.pending() > 0
                ? "INCONCLUSIVE - " + s.pending() + " sequences still pending; stop the publisher, "
                        + "let it drain, then re-check with ?finalize=true"
                : (s.missing() == 0
                        ? "NO LOSS DETECTED" + (s.unsent() > 0
                                ? " (" + s.unsent() + " never left the producer)" : "")
                        : "LOSS DETECTED: " + s.missing() + " message(s)");

        return "<html><body><h3>" + verdict + "</h3><table>"
                + row("run id", s.runId())
                + row("produced (sequences issued)", s.produced())
                + row("unsent (send failed locally)", s.unsent())
                + row("offered to the cluster", s.offered())
                + row("received", s.received())
                + row("missing (settled, never arrived)", s.missing())
                + row("pending (in flight, unjudged)", s.pending())
                + row("duplicates", s.duplicates())
                + row("late (arrived after settling)", s.late())
                + row("out of order", s.outOfOrder())
                + row("foreign (other runs, ignored)", s.foreign())
                + "</table><p>Duplicates and reordering are expected across partitions; Kafka only "
                + "orders within one. A send that failed locally was never the cluster's to lose, "
                + "so those are retired as <em>unsent</em> rather than counted as missing - "
                + "<em>offered</em> is what the cluster was actually asked to carry, and it is "
                + "<em>missing</em> against that figure which indicates real loss.</p></body></html>";
    }

    private static String row(String label, Object value) {
        return "<tr><td>" + label + "</td><td>" + value + "</td></tr>";
    }

    @GetMapping("/histogram")
    public String histogram() {
        HistogramSnapshot snapshot = latency.takeSnapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>latency at or below (ms)</th><th>cumulative count</th></tr>");
        for (CountAtBucket bucket : snapshot.histogramCounts()) {
            sb.append("<tr><td>").append(String.format("%.3f", bucket.bucket(TimeUnit.MILLISECONDS)))
                    .append("</td><td>").append((long) bucket.count())
                    .append("</td></tr>\n");
        }
        sb.append("</table>");

        sb.append("<table><tr><th>percentile</th><th>ms</th></tr>");
        for (ValueAtPercentile p : snapshot.percentileValues()) {
            sb.append("<tr><td>p").append(String.format("%.0f", p.percentile() * 100))
                    .append("</td><td>").append(String.format("%.3f", p.value(TimeUnit.MILLISECONDS)))
                    .append("</td></tr>\n");
        }
        sb.append("</table>");

        sb.append("<p>count: ").append(snapshot.count())
                .append(", mean: ").append(String.format("%.3f", snapshot.mean(TimeUnit.MILLISECONDS)))
                .append(" ms, max: ").append(String.format("%.3f", snapshot.max(TimeUnit.MILLISECONDS)))
                .append(" ms, skipped: ").append((long) skipped.count())
                .append("</p>");
        return sb.toString();
    }

    private static class ItemStat {
        String id;
        double millis;
        int size;
    }
}
