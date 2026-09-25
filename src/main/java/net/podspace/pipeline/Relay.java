package net.podspace.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.MessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Echoes messages from one topic to another, verbatim.
 *
 * <p>This is the far end of a round-trip latency measurement: the origin instance publishes to the
 * outbound topic and consumes the return topic, so both timestamps it compares come from its own
 * clock and the result carries no cross-machine clock skew. That is what makes the measurement
 * usable between availability zones, where skew can easily exceed the latency being measured.
 *
 * <p>Messages are relayed as raw bytes and never decoded. That preserves the originating timestamp
 * exactly - re-encoding would rewrite it and destroy the measurement - and means the relay works
 * for any payload, JSON or Avro, not just the temperature messages this app happens to generate.
 * Bytes rather than strings matters here specifically: Avro is not valid UTF-8, so a relay that
 * went through a String would corrupt every message it forwarded.
 */
public class Relay implements EngineStatus {
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

    @Override
    public boolean isHealthy() {
        return loop.isHealthy();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public boolean isAttached() {
        return reader.isReady();
    }

    /** Whether the inbound side is actually attached; see MessageReader.isReady(). */
    public boolean isReaderReady() {
        return reader.isReady();
    }

    @Override
    public long getTotalFailures() {
        return loop.getTotalFailures();
    }

    @Override
    public String getLastFailure() {
        return loop.getLastFailure();
    }

    @Override
    public java.time.Duration getCurrentOutage() {
        return loop.getCurrentOutage();
    }

    @Override
    public void onRecovery(RecoveryObserver observer) {
        loop.setRecoveryListener(observer::recovered);
    }

    /** Surfaces the forwarded count on /actuator/health, alongside the metric. */
    @Override
    public Map<String, Object> details() {
        return Map.of("relayed", getRelayedCount());
    }

    public long getRelayedCount() {
        return (long) relayed.count();
    }

    /** One pass: forward whatever is currently available, unchanged. */
    private void relayBatch() {
        List<byte[]> batch = reader.readMessage();
        if (batch.isEmpty()) {
            logger.debug("Nothing to echo.");
            return;
        }
        for (byte[] message : batch) {
            writer.writeMessage(message);
            relayed.increment();
        }
        logger.debug("Echoed {} messages.", batch.size());
    }
}
