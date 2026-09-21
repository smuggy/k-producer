package net.podspace.messaging.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.podspace.messaging.MessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class KafkaWriter implements MessageWriter {
    private static final Logger logger = LoggerFactory.getLogger(KafkaWriter.class);
    private final String topicName;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry registry;
    private final Counter acknowledged;
    private final Timer ackLatency;
    /** Error counters by exception type, created on first sight. Bounded by the client's error set. */
    private final Map<String, Counter> errors = new ConcurrentHashMap<>();

    public KafkaWriter(KafkaTemplate<String, String> kafkaTemplate, String topicName, MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
        this.registry = registry;
        this.acknowledged = Counter.builder("kproducer.producer.acknowledged")
                .description("Messages the broker acknowledged. Counted on the ack, not on send, "
                        + "so it reflects what actually landed - which is also the number any "
                        + "delivery reconciliation must compare against")
                .tag("topic", topicName)
                .register(registry);
        this.ackLatency = Timer.builder("kproducer.producer.ack.latency")
                .description("Time from send() to broker acknowledgement, covering the full async "
                        + "path including client-side buffering")
                .tag("topic", topicName)
                .register(registry);
    }

    @Override
    public void writeMessage(String message) {
        writeMessage(null, message);
    }

    @Override
    public void writeMessage(String key, String message) {
        long startNanos = System.nanoTime();
        CompletableFuture<SendResult<String, String>> future;
        try {
            // A null key leaves partition choice to the sticky partitioner, which is the right
            // default for pure throughput; a key pins the message to a partition by hash.
            future = kafkaTemplate.send(topicName, key, message);
        } catch (RuntimeException e) {
            // Sends fail on two different paths and both must be counted. When the brokers are
            // entirely unreachable the client cannot even fetch metadata, so send() blocks for
            // max.block.ms and then throws here - the callback below never runs. Rethrow after
            // counting so the publisher loop still sees the failure and backs off.
            recordError(e);
            throw e;
        }
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                acknowledged.increment();
                ackLatency.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                logger.debug("Sent message with offset=[{}]", result.getRecordMetadata().offset());
            } else {
                // Send failures arrive here, asynchronously - they never surface to the publisher
                // loop, so without this counter a broker outage is invisible except in the log.
                recordError(ex);
                logger.info("Unable to send message due to {}: {}",
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        });
    }

    private void recordError(Throwable ex) {
        // Unwrap the CompletionException the future wraps the real cause in, so the tag names the
        // actual Kafka failure rather than the plumbing.
        Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
        String type = cause.getClass().getSimpleName();
        errors.computeIfAbsent(type, t -> Counter.builder("kproducer.producer.errors")
                .description("Failed sends, by exception type")
                .tag("topic", topicName)
                .tag("exception", t)
                .register(registry)).increment();
    }
}
