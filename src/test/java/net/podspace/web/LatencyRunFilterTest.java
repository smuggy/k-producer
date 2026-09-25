package net.podspace.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
import net.podspace.messaging.MessageReader;
import net.podspace.pipeline.DeliveryLedger;
import net.podspace.pipeline.Watcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Latency must be measured only for messages this run produced.
 *
 * <p>A message left on the topic by an earlier run carries that run's timestamp, so subtracting it
 * measures how long the message sat on the topic - potentially days - rather than anything about
 * the pipeline. Because a timer's histogram is cumulative, a single backlog replay used to poison
 * the latency metrics for the remainder of the process lifetime.
 */
class LatencyRunFilterTest {

    /** Hands over a fixed batch once, then nothing, so the watcher loop settles. */
    private static class OneShotReader implements MessageReader {
        private final List<byte[]> batch;
        private final CountDownLatch delivered = new CountDownLatch(1);
        private boolean sent;

        OneShotReader(List<byte[]> batch) { this.batch = batch; }

        @Override public synchronized List<byte[]> readMessage() {
            if (sent) {
                return List.of();
            }
            sent = true;
            delivered.countDown();
            return batch;
        }
        @Override public boolean isReady() { return true; }
    }

    private static byte[] message(String runId, long seq) {
        return Temperature.createCelsiusTemp(21.5, "", runId, seq)
                .toJsonString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void latencyIsRecordedOnlyForThisRunWhileReconciliationStillSeesEverything() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeliveryLedger ledger = new DeliveryLedger(registry);
        String mine = ledger.getRunId();

        // Two of ours, three from previous runs - the shape of a topic with backlog on it.
        OneShotReader reader = new OneShotReader(List.of(
                message(mine, ledger.nextSequence()),
                message(mine, ledger.nextSequence()),
                message("run-from-yesterday", 1),
                message("run-from-yesterday", 2),
                message("a-third-run", 99)));

        Watcher<Temperature> watcher = new Watcher<>(new TemperatureConsumer(), reader);
        new ConsumerController(watcher, registry, ledger);   // constructor wires the sink

        watcher.initiate();
        try {
            Assertions.assertTrue(reader.delivered.await(5, TimeUnit.SECONDS));
            Thread.sleep(500); // let the sink drain the batch
        } finally {
            watcher.teardown();
        }

        long measured = registry.get("kproducer.message.latency").timer().count();
        Assertions.assertEquals(2, measured,
                "only messages from this run may contribute a latency sample");

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(2, s.received(), "our own messages are reconciled as received");
        Assertions.assertEquals(3, s.foreign(),
                "foreign messages must still be counted, just not measured");
        Assertions.assertEquals(0, s.missing());
    }

    @Test
    void aTopicOfNothingButBacklogProducesNoLatencySamplesAtAll() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeliveryLedger ledger = new DeliveryLedger(registry);

        OneShotReader reader = new OneShotReader(List.of(
                message("old-run", 1), message("old-run", 2), message("old-run", 3)));

        Watcher<Temperature> watcher = new Watcher<>(new TemperatureConsumer(), reader);
        new ConsumerController(watcher, registry, ledger);

        watcher.initiate();
        try {
            Assertions.assertTrue(reader.delivered.await(5, TimeUnit.SECONDS));
            Thread.sleep(500);
        } finally {
            watcher.teardown();
        }

        Assertions.assertEquals(0, registry.get("kproducer.message.latency").timer().count(),
                "backlog alone must not produce a latency distribution");
        Assertions.assertEquals(3, ledger.snapshot().foreign());
    }
}
