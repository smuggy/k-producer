package net.podspace.config;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka rejects {@code delivery.timeout.ms < linger.ms + request.timeout.ms}, and the producer is
 * built lazily on first send - so an invalid combination does not fail at startup, it makes every
 * send throw ConfigException at runtime, which reads like a broker outage. Constructing a
 * KafkaProducer runs exactly that validation in its constructor - before any network use - so it
 * can be checked without a broker. Note the check lives in KafkaProducer, not ProducerConfig:
 * building a ProducerConfig with an invalid value succeeds silently.
 */
class ProducerTimeoutConfigTest {

    /** Mirrors what AppConfig.producerFactory builds. */
    private static Map<String, Object> producerProps(int deliveryTimeoutMs) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        return props;
    }

    @Test
    void theShippedDefaultIsAcceptedByKafka() {
        // 40000, matching myapp.kafka.deliveryTimeoutMs in application.yaml.
        Assertions.assertDoesNotThrow(() -> {
            try (var producer = new KafkaProducer<String, String>(producerProps(40_000))) {
                Assertions.assertNotNull(producer);
            }
        });
    }

    @Test
    void thirtySecondsIsRejected() {
        // Pins the mistake: 30000 looks like the floor if linger.ms is assumed to be 0, but Kafka 4
        // defaults linger.ms to 5, making the real minimum 30005. This shipped once and made every
        // send fail with ConfigException against a perfectly healthy broker.
        // KafkaProducer wraps the ConfigException, so assert on the wrapper and the reason.
        KafkaException thrown = Assertions.assertThrows(KafkaException.class,
                () -> new KafkaProducer<String, String>(producerProps(30_000)).close(),
                "if this stops throwing, Kafka's defaults changed - revisit the shipped default");
        String reason = thrown.getCause() != null ? thrown.getCause().getMessage() : thrown.getMessage();
        Assertions.assertTrue(reason != null && reason.contains("delivery.timeout.ms"),
                "expected the delivery.timeout.ms constraint to be the reason, got: " + reason);
    }

    @Test
    void theDefaultClearsTheFloorWithRoomToSpare() {
        ProducerConfig config = new ProducerConfig(producerProps(40_000));
        long linger = config.getLong(ProducerConfig.LINGER_MS_CONFIG);
        int requestTimeout = config.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        Assertions.assertTrue(40_000 > linger + requestTimeout,
                "shipped default " + 40_000 + " must exceed linger " + linger
                        + " + requestTimeout " + requestTimeout);
    }
}
