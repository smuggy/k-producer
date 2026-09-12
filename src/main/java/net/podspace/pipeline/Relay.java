package net.podspace.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.MessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Echoes messages from one topic to another, verbatim.
 *
 * <p>This is the far end of a round-trip latency measurement: the origin instance publishes to the
 * outbound topic and consumes the return topic, so both timestamps it compares come from its own
 * clock and the result carries no cross-machine clock skew. That is what makes the measurement
 * usable between availability zones, where skew can easily exceed the latency being measured.
 *
 * <p>Messages are relayed as raw strings and never deserialized. That preserves the originating
 * timestamp exactly - re-serializing would rewrite it and destroy the measurement - and means the
 * relay works for any payload, not just the temperature messages this app happens to generate.
 */
public class Relay {
    private static final Logger logger = LoggerFactory.getLogger(Relay.class);
    private static final long PAUSE_MILLIS = 5_000;
    private final MessageReader reader;
    private final MessageWriter writer;
    private final WorkerLoop loop;
    private final Counter relayed;

    public Relay(MessageReader reader, MessageWriter writer, MeterRegistry registry) {
        this.reader = reader;
        this.writer = writer;
        this.relayed = Counter.builder("kproducer.relay.messages")
                .description("Messages echoed from the inbound topic to the return topic")
                .register(registry);
        this.loop = new WorkerLoop("relay", PAUSE_MILLIS, this::relayBatch);
    }

    public void initiate() {
        loop.initiate();
    }

    public void teardown() {
        loop.teardown();
    }

    public void quit() {
        loop.teardown();
    }

    public void pause() {
        loop.pause();
    }

    public void resume() {
        loop.resume();
    }

    public boolean isHealthy() {
        return loop.isHealthy();
    }

    public long getTotalFailures() {
        return loop.getTotalFailures();
    }

    public String getLastFailure() {
        return loop.getLastFailure();
    }

    public long getRelayedCount() {
        return (long) relayed.count();
    }

    /** One pass: forward whatever is currently available, unchanged. */
    private void relayBatch() {
        List<String> batch = reader.readMessage();
        if (batch.isEmpty()) {
            logger.debug("Nothing to echo.");
            return;
        }
        for (String message : batch) {
            writer.writeMessage(message);
            relayed.increment();
        }
        logger.debug("Echoed {} messages.", batch.size());
    }
}
