package net.podspace.producer;

import net.podspace.consumer.ValueEnvelope;
import net.podspace.consumer.Watcher;
import net.podspace.domain.Temperature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@RestController
@RequestMapping("/consumer")
public class ConsumerController {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerController.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final Comparator<ItemStat> BY_LATENCY = Comparator.comparingDouble(i -> i.timeDifference);
    private static final int BUCKET_COUNT = 20;
    /** Cap on retained samples, matching the return queue, so a long run cannot exhaust the heap. */
    private static final int MAX_SAMPLES = 200_000;
    private final Watcher<Temperature> watcher;
    private final BlockingQueue<ValueEnvelope<Temperature>> items;
    /** Guarded by {@link #statsLock}; oldest samples are evicted once MAX_SAMPLES is reached. */
    private final Deque<ItemStat> stats;
    private final Object statsLock = new Object();

    public ConsumerController(Watcher<Temperature> watcher) {
        this.stats = new ArrayDeque<>();
        this.watcher = watcher;
        this.items = new ArrayBlockingQueue<>(200_000);
        this.watcher.setReturnQueue(items);
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

    @GetMapping("/stats")
    public String statistics() {
        List<ItemStat> samples = drainAndSnapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>time id</th><th>time difference (ms)</th><th>size</th></tr>");
        for (ItemStat t : samples) {
            sb.append("<tr><td>").append(t.id)
                    .append("</td><td>").append(String.format("%.3f", t.timeDifference))
                    .append("</td><td>").append(t.size)
                    .append("</td></tr>\n");
            logger.debug("Id: {} milliseconds: {}", t.id, t.timeDifference);
        }

        sb.append("</table><p>total messages: ").append(samples.size());
        return sb.toString();
    }

    @GetMapping("/histogram")
    public String histogram() {
        // Drain here too: /histogram used to report nothing unless /stats happened to be called first.
        List<ItemStat> samples = warmUpTrimmed(drainAndSnapshot());

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>limit (ms)</th><th>count</th></tr>");
        for (HistogramEntry e : getHistogram(samples)) {
            sb.append("<tr><td>").append(String.format("%.3f", e.upper))
                    .append("</td><td>").append(e.count)
                    .append("</td></tr>\n");
        }

        sb.append("</table>");
        sb.append("<p>Average is: ").append(String.format("%.3f", average(samples))).append(" ms</p>");
        return sb.toString();
    }

    /**
     * Moves everything currently on the return queue into the retained sample set and returns a
     * snapshot of it, so callers never iterate the shared collection directly.
     */
    private List<ItemStat> drainAndSnapshot() {
        List<ItemStat> drained = new ArrayList<>();
        ValueEnvelope<Temperature> envelope;
        // poll() rather than isEmpty()+poll(): another request thread draining concurrently would
        // otherwise leave us holding a null.
        while ((envelope = items.poll()) != null) {
            ItemStat stat = toStat(envelope);
            if (stat != null) {
                drained.add(stat);
            }
        }

        synchronized (statsLock) {
            stats.addAll(drained);
            while (stats.size() > MAX_SAMPLES) {
                stats.pollFirst();
            }
            return new ArrayList<>(stats);
        }
    }

    /** Returns null for any message we cannot derive a latency from, rather than failing the request. */
    private ItemStat toStat(ValueEnvelope<Temperature> envelope) {
        Temperature t = envelope.item;
        // Jackson leaves these null for anything on the topic that is not one of our messages.
        if (t == null || t.getTime() == null || envelope.time == null) {
            logger.info("Skipping message with no usable timestamp.");
            return null;
        }
        try {
            LocalDateTime readTime = LocalDateTime.parse(envelope.time, formatter);
            LocalDateTime writeTime = LocalDateTime.parse(t.getTime(), formatter);
            Duration d = Duration.between(writeTime, readTime);
            ItemStat stat = new ItemStat();
            stat.id = t.getTimeId();
            // Whole milliseconds plus the sub-millisecond remainder. The previous
            // toSeconds() + getNano()/1e6 mixed units and under-reported anything over a second.
            stat.timeDifference = d.toNanos() / 1_000_000.0;
            stat.size = envelope.size;
            return stat;
        } catch (DateTimeParseException e) {
            logger.info("Skipping message with unparsable timestamp: {}", t.getTime());
            return null;
        }
    }

    /**
     * Drops the first sample, whose latency includes consumer start-up and skews the distribution.
     * Kept only when there is more than one sample to report.
     */
    private List<ItemStat> warmUpTrimmed(List<ItemStat> samples) {
        return samples.size() > 1 ? samples.subList(1, samples.size()) : samples;
    }

    private List<HistogramEntry> getHistogram(List<ItemStat> samples) {
        List<HistogramEntry> buckets = new ArrayList<>(BUCKET_COUNT);
        if (samples.isEmpty()) {
            return buckets;
        }

        double max = Collections.max(samples, BY_LATENCY).timeDifference;
        if (max <= 0) {
            // Every latency is zero or negative (identical timestamps, or producer/consumer clock
            // skew). There is no meaningful range to divide, so report a single bucket.
            HistogramEntry only = new HistogramEntry(max);
            for (int i = 0; i < samples.size(); i++) {
                only.increment();
            }
            buckets.add(only);
            return buckets;
        }

        double spread = max / BUCKET_COUNT;
        for (int j = 0; j < BUCKET_COUNT; j++) {
            buckets.add(new HistogramEntry(spread * (j + 1)));
        }
        for (ItemStat i : samples) {
            int location = (int) (i.timeDifference / spread);
            if (location >= BUCKET_COUNT) {
                location = BUCKET_COUNT - 1; // the slowest sample lands on the final boundary
            } else if (location < 0) {
                location = 0;                // negative latency from clock skew
            }
            buckets.get(location).increment();
        }
        return buckets;
    }

    private double average(List<ItemStat> samples) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        double total = 0;
        for (ItemStat i : samples) {
            total += i.timeDifference;
        }
        return total / samples.size();
    }

    private static class ItemStat {
        String id;
        double timeDifference;
        int size;
    }

    private static class HistogramEntry {
        double upper;
        int count;

        HistogramEntry(double upper) {
            this.upper = upper;
            this.count = 0;
        }

        void increment() {
            this.count++;
        }

        @Override
        public String toString() {
            return upper + "\t" + count + "\n";
        }
    }
}
