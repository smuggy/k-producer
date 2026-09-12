package net.podspace.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import net.podspace.domain.Temperature;
import net.podspace.pipeline.ValueEnvelope;
import net.podspace.pipeline.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
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
    private final String role;
    private final Timer latency;
    private final Counter skipped;
    /** Guarded by {@link #recentLock}; oldest entries evicted past RECENT_SAMPLES. */
    private final Deque<ItemStat> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    public ConsumerController(Watcher<Temperature> watcher,
                              MeterRegistry registry,
                              @Value("${myapp.role:loopback}") String role) {
        this.watcher = watcher;
        this.role = role;
        this.latency = Timer.builder("kproducer.message.latency")
                .description("End-to-end latency from message creation to consumption")
                // Tagged by role because the number means different things: loopback is one-way,
                // origin is a full round trip through the echo instance. Mixing them in one series
                // would make the histogram meaningless.
                .tag("role", role)
                .publishPercentiles(0.5, 0.95, 0.99)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry);
        this.skipped = Counter.builder("kproducer.message.skipped")
                .description("Consumed messages with no usable timestamp, so no latency recorded")
                .tag("role", role)
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
        // Jackson leaves these null for anything on the topic that is not one of our messages.
        if (t == null || t.getTime() == null || envelope.time() == null) {
            skipped.increment();
            logger.info("Skipping message with no usable timestamp.");
            return;
        }
        try {
            LocalDateTime readTime = LocalDateTime.parse(envelope.time(), formatter);
            LocalDateTime writeTime = LocalDateTime.parse(t.getTime(), formatter);
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
