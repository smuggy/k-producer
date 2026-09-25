package net.podspace.messaging.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.podspace.messaging.MessageReader;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaReader implements MessageReader, ConsumerRebalanceListener {
    private static final Logger logger = LoggerFactory.getLogger(KafkaReader.class);
    private final Consumer<String, String> consumer;
    private final MeterRegistry registry;
    private final String topicName;
    // Per-partition meters, created on first sight of a partition. Cardinality is bounded by the
    // topic's partition count.
    private final Map<Integer, Counter> recordCounters = new ConcurrentHashMap<>();
    private final Map<Integer, Timer> ageTimers = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> lastOffsets = new ConcurrentHashMap<>();
    /**
     * Whether this consumer currently holds a partition assignment. Maintained from the rebalance
     * callbacks, which run on the polling thread, rather than by calling consumer.assignment():
     * KafkaConsumer permits only single-threaded access, so querying it from a health check
     * request thread would throw ConcurrentModificationException.
     */
    private volatile boolean assigned;
    /**
     * How long a run of empty polls may pass before the reader actively checks that the brokers
     * are still reachable, and how long that check may take.
     */
    private static final Duration REACHABILITY_CHECK_INTERVAL = Duration.ofSeconds(30);
    /** Zero or negative disables the check entirely - see myapp.kafka.reachabilityCheckSeconds. */
    private final Duration reachabilityCheckInterval;
    private static final Duration REACHABILITY_CHECK_TIMEOUT = Duration.ofSeconds(5);
    /** Worker-thread only: last time we had positive evidence the cluster was reachable. */
    private long lastContactNanos = System.nanoTime();

    public KafkaReader(ConsumerFactory<String, String> consumer, String topicName, MeterRegistry registry) {
        this(consumer, topicName, registry, REACHABILITY_CHECK_INTERVAL);
    }

    public KafkaReader(ConsumerFactory<String, String> consumer, String topicName,
                       MeterRegistry registry, Duration reachabilityCheckInterval) {
        this(consumer.createConsumer(), topicName, registry, reachabilityCheckInterval);
    }

    /** Package-private: takes the consumer directly, so a test can supply a stub. */
    KafkaReader(Consumer<String, String> consumer, String topicName, MeterRegistry registry,
                Duration reachabilityCheckInterval) {
        this.registry = registry;
        this.topicName = topicName;
        this.consumer = consumer;
        this.reachabilityCheckInterval = reachabilityCheckInterval;
        this.consumer.subscribe(Collections.singletonList(topicName), this);
    }

    public List<String> readMessage() {
        List<String> ret = new ArrayList<>();
        logger.debug("Listening for message.");

        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        for (ConsumerRecord<String, String> r : records) {
            ret.add(r.value());
            recordMetadata(r);
        }
        if (records.isEmpty()) {
            // Before commitSync, deliberately. commitSync throws when the coordinator is gone, and
            // it used to sit above this - so on a real outage it always threw first and this check
            // never ran at all, in precisely the scenario it exists for. Ordering it first makes it
            // the explicit guarantee rather than dead code, and leaves commitSync as the backstop.
            verifyReachable();
        } else {
            lastContactNanos = System.nanoTime();
        }
        consumer.commitSync(Duration.ofSeconds(1));
        return ret;
    }

    /**
     * Confirms the cluster is still there when nothing is arriving.
     *
     * <p>An idle topic and an unreachable cluster look identical from poll(): both return an empty
     * batch and throw nothing. The partition assignment does not separate them either, because
     * {@link #assigned} is only cleared by a rebalance callback, and a client that has lost every
     * broker never learns it lost its partitions - so the flag stays stale at true through a total
     * outage. Without this check a dead cluster reports healthy indefinitely.
     *
     * <p>endOffsets is a real round trip to the partition leaders and throws when they cannot be
     * reached, so the failure reaches {@code WorkerLoop}, which backs off and marks the loop
     * unhealthy - which is what the readiness probes read. Bounded and single-attempt, leaving the
     * retry to the loop, per the blocking-call rule in CLAUDE.md.
     *
     * <p>Runs on the polling thread, inside readMessage, because KafkaConsumer permits only
     * single-threaded access. It fires at most once per interval, and only while idle, so a busy
     * reader never pays for it.
     *
     * <p><b>Overlap with commitSync.</b> commitSync also makes a coordinator round trip and also
     * throws when the brokers are gone, so it detects most outages too. It is not a substitute
     * though: it only helps while there is an offset to commit, which stops being true under
     * enable.auto.commit or for a reader that never commits. This check runs <em>before</em> it
     * for that reason - when it sat after, commitSync threw first every time and this never
     * executed during an actual outage. Set myapp.kafka.reachabilityCheckSeconds to 0 to rely on
     * the commit path alone.
     */
    private void verifyReachable() {
        if (reachabilityCheckInterval.isZero() || reachabilityCheckInterval.isNegative()) {
            return; // disabled by configuration
        }
        if (System.nanoTime() - lastContactNanos < reachabilityCheckInterval.toNanos()) {
            return;
        }
        Collection<TopicPartition> assignment = consumer.assignment();
        if (assignment.isEmpty()) {
            // Nothing to ask about, and isReady() already reports this as not attached.
            return;
        }
        logger.debug("No records for {}s; verifying the cluster is reachable.",
                reachabilityCheckInterval.toSeconds());
        consumer.endOffsets(assignment, REACHABILITY_CHECK_TIMEOUT);
        lastContactNanos = System.nanoTime();
    }

    /**
     * Records where each message came from. Without this the partition, offset and Kafka
     * timestamp are discarded, which is exactly the information needed to spot a hot or stalled
     * partition, uneven key distribution, or a single slow replica.
     */
    private void recordMetadata(ConsumerRecord<String, String> r) {
        int partition = r.partition();

        recordCounters.computeIfAbsent(partition, p -> Counter.builder("kproducer.consumer.records")
                .description("Records consumed, by partition")
                .tag("topic", topicName)
                .tag("partition", String.valueOf(p))
                .register(registry)).increment();

        lastOffsets.computeIfAbsent(partition, p -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("kproducer.consumer.last.offset", holder, AtomicLong::get)
                    .description("Offset of the most recent record consumed, by partition")
                    .tag("topic", topicName)
                    .tag("partition", String.valueOf(p))
                    .register(registry);
            return holder;
        }).set(r.offset());

        // Age of the record at the moment we consumed it. Note the reference point depends on the
        // topic's message.timestamp.type: CreateTime (default) is the producer's clock,
        // LogAppendTime is the broker's. Either way a rising age means the consumer is behind.
        if (r.timestamp() > 0) {
            long ageMillis = System.currentTimeMillis() - r.timestamp();
            if (ageMillis >= 0) {
                ageTimers.computeIfAbsent(partition, p -> Timer.builder("kproducer.consumer.record.age")
                        .description("Time from the record's Kafka timestamp to consumption, by partition")
                        .tag("topic", topicName)
                        .tag("partition", String.valueOf(p))
                        .register(registry)).record(ageMillis, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * False while this consumer holds no partitions - it has not yet joined the group, or cannot
     * reach the brokers. Distinguishes "connected but idle" from "not consuming at all", which a
     * poll that simply returns empty records cannot.
     */
    @Override
    public boolean isReady() {
        return assigned;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        assigned = false;
        try {
            consumer.commitSync(Duration.ofSeconds(5));
        } catch (KafkaException e) {
            logger.warn("Failed to commit offsets during rebalance for partitions {}", partitions, e);
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        assigned = !partitions.isEmpty();
        // Deliberately does NOT seek: this fires on every rebalance, so seeking to the beginning
        // here discarded committed offsets and replayed the whole topic on every restart, deploy
        // or scale event. "Start from the beginning when this group has no committed offset" is
        // auto.offset.reset=earliest, set on the consumer factory.
        logger.info("Partitions assigned, resuming from committed offsets: {}", partitions);
    }

    /**
     * Closes the underlying consumer. Called by Spring when the application context shuts down -
     * see the destroyMethod on the messageReader bean. It is deliberately NOT tied to the watcher
     * loop stopping: the consumer is a singleton that outlives any one /consumer/start cycle, and
     * closing it there left a later /consumer/start with an unusable consumer.
     */
    @Override
    public void close() {
        logger.info("Closing kafka consumer.");
        consumer.close();
    }
}
