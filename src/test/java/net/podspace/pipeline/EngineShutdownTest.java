package net.podspace.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageGenerator;
import net.podspace.messaging.MessageReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The engines are wired with {@code @Bean(destroyMethod = "teardown")}, so Spring stops their
 * worker loops when the context closes. These cover the contract that relies on: teardown must
 * actually stop the loop, and must be safe to call when the loop was never started or was already
 * stopped - Spring calls it unconditionally, including on a publisher a user already stopped
 * through /publisher/stop.
 */
class EngineShutdownTest {

    private static MessageGenerator generator() {
        return new MessageGenerator() {
            @Override public Generated createMessage() { return Generated.untracked("{}"); }
            @Override public void setFillerSize(int size) { }
            @Override public int getFillerSize() { return 0; }
            @Override public int adjustFillerSize(int delta) { return 0; }
        };
    }

    private static MessageReader idleReader() {
        return new MessageReader() {
            @Override public List<byte[]> readMessage() { return Collections.emptyList(); }
            @Override public boolean isReady() { return true; }
        };
    }

    @Test
    void tearingDownAPublisherStopsItsLoop() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        Publisher p = new Publisher(generator(), m -> published.countDown(), ledger);
        p.setSleep(1);

        p.initiate();
        Assertions.assertTrue(published.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(p.isRunning());

        p.teardown();
        Assertions.assertFalse(p.isRunning(), "the loop must be stopped, not merely signalled");
    }

    @Test
    void tearingDownAnUnstartedEngineIsHarmless() {
        // Spring calls destroyMethod on every engine bean, including ones never started - the
        // publisher and watcher are started through REST, so a pod that is rolled without anyone
        // hitting /publisher/start shuts down with the loop untouched.
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        Publisher p = new Publisher(generator(), m -> { }, ledger);
        Assertions.assertFalse(p.isRunning());
        Assertions.assertDoesNotThrow(p::teardown);
        Assertions.assertFalse(p.isRunning());
    }

    @Test
    void tearingDownTwiceIsHarmless() {
        // /publisher/stop then a context close is the ordinary case, not an edge case.
        Relay relay = new Relay(idleReader(), m -> { }, new SimpleMeterRegistry());
        relay.initiate();
        relay.teardown();
        Assertions.assertDoesNotThrow(relay::teardown);
        Assertions.assertFalse(relay.isRunning());
    }

    @Test
    void anEngineCanBeRestartedAfterTeardown() {
        // teardown must leave the engine reusable: /consumer/stop followed by /consumer/start is a
        // normal operation, and the reader is a singleton that outlives any one cycle.
        Watcher<String> w = new Watcher<>(s -> java.util.Optional.empty(), idleReader());
        w.initiate();
        w.teardown();
        Assertions.assertFalse(w.isRunning());
        w.initiate();
        Assertions.assertTrue(w.isRunning(), "an engine must be startable again after teardown");
        w.teardown();
    }
}
