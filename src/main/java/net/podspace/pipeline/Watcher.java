package net.podspace.pipeline;

import net.podspace.messaging.MessageConsumer;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class Watcher<T extends Comparable<T>> implements EngineStatus {
    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);
    private static final long PAUSE_MILLIS = 5_000;
    private final MessageConsumer<T> consumer;
    private final MessageReader reader;
    private final WorkerLoop loop;
    /**
     * Consumes each envelope as it is read, on the watcher thread. Replaces the old hand-off
     * queue, which buffered up to 200k whole messages and silently discarded samples once full.
     * Must be quick and must not throw.
     */
    private volatile Consumer<ValueEnvelope<T>> sink;

    public Watcher(MessageConsumer<T> c, MessageReader r) {
        this.reader = r;
        this.consumer = c;
        // No exit hook: the reader is a singleton that outlives this loop, so closing it when the
        // loop stops left a later /consumer/start holding an unusable reader. The container owns
        // the reader's lifetime and closes it at shutdown (see AppConfig.messageReader).
        this.loop = new WorkerLoop("watcher", PAUSE_MILLIS, this::pollAndRecord);
    }

    public void setSink(Consumer<ValueEnvelope<T>> sink) {
        this.sink = sink;
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
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public boolean isHealthy() {
        return loop.isHealthy();
    }

    /** Whether the inbound side is actually attached; see MessageReader.isReady(). */
    @Override
    public boolean isAttached() {
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

    /** One pass: read whatever is available and record a latency sample for each parsed message. */
    private void pollAndRecord() {
        List<byte[]> list = reader.readMessage();
        if (list.isEmpty()) {
            logger.info("No message available... wait again.");
            return;
        }
        for (byte[] mess : list) {
            Optional<Pair<T, Integer>> val = consumer.getMessage(mess);
            if (val.isEmpty()) {
                // Logged as a length, not content: the payload may be binary, and dumping raw
                // Avro into the log is noise at best.
                logger.info("No value present or parsable in message of {} bytes", mess.length);
                continue;
            }
            logger.debug("Value is: {}", val.get());
            Consumer<ValueEnvelope<T>> target = sink;
            if (target == null) {
                logger.info("No sink provided, dropping item.");
                continue;
            }
            ValueEnvelope<T> envelope = new ValueEnvelope<>(
                    val.get().a(), Instant.now().toString(), val.get().b());
            try {
                target.accept(envelope);
            } catch (RuntimeException e) {
                // Never let a sink failure kill the read loop, but do not hide it either.
                logger.warn("Sink rejected item", e);
            }
        }
    }
}
