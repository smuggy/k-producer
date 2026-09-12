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

    public KafkaReader(ConsumerFactory<String, String> consumer, String topicName, MeterRegistry registry) {
        this.registry = registry;
        this.topicName = topicName;
        this.consumer = consumer.createConsumer();
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
        consumer.commitSync(Duration.ofSeconds(1));
        return ret;
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

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        try {
            consumer.commitSync(Duration.ofSeconds(5));
        } catch (KafkaException e) {
            logger.warn("Failed to commit offsets during rebalance for partitions {}", partitions, e);
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
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
