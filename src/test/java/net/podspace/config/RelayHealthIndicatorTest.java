package net.podspace.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageReader;
import net.podspace.pipeline.Relay;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Covers the readiness contributor for the echo role. The point of these is the liveness/readiness
 * split: a relay that cannot reach its brokers must report DOWN for readiness while the process
 * itself stays alive, so Kubernetes pulls it out of service rather than restarting it during the
 * very outage the tool was deployed to observe.
 */
class RelayHealthIndicatorTest {

    private static Relay relayOver(MessageReader reader) {
        return new Relay(reader, m -> {}, new SimpleMeterRegistry());
    }

    @Test
    void reportsDownWhenTheReaderHasNoPartitionAssignment() {
        Relay relay = relayOver(new MessageReader() {
            @Override public List<String> readMessage() { return Collections.emptyList(); }
            @Override public boolean isReady() { return false; }
        });

        Health h = new RelayHealthIndicator(relay).health();
        Assertions.assertEquals(Status.DOWN, h.getStatus(),
                "an unattached reader means nothing can arrive, so the instance is not ready");
        Assertions.assertEquals(false, h.getDetails().get("readerAttached"));
    }

    @Test
    void reportsUpWhenAttachedAndTheLoopIsHealthy() {
        Relay relay = relayOver(new MessageReader() {
            @Override public List<String> readMessage() { return Collections.emptyList(); }
            @Override public boolean isReady() { return true; }
        });

        Health h = new RelayHealthIndicator(relay).health();
        Assertions.assertEquals(Status.UP, h.getStatus());
    }

    @Test
    void reportsDownAndNamesTheFailureWhileTheLoopIsErroring() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        Relay relay = relayOver(new MessageReader() {
            @Override public List<String> readMessage() {
                failed.countDown();
                throw new IllegalStateException("broker is gone");
            }
            @Override public boolean isReady() { return true; }
        });

        relay.initiate();
        try {
            Assertions.assertTrue(failed.await(5, TimeUnit.SECONDS));
            // The loop records the failure just after the body throws; give it a moment to land.
            Health h = null;
            for (int i = 0; i < 50 && (h == null || h.getStatus() != Status.DOWN); i++) {
                Thread.sleep(20);
                h = new RelayHealthIndicator(relay).health();
            }
            Assertions.assertEquals(Status.DOWN, h.getStatus(),
                    "a relay whose reads keep throwing is not ready");
            Assertions.assertTrue(String.valueOf(h.getDetails().get("lastFailure")).contains("broker is gone"),
                    "the failure should be visible in the details, since show-details is always");
        } finally {
            relay.teardown();
        }
    }

    @Test
    void countsWhatItRelays() throws Exception {
        CountDownLatch relayed = new CountDownLatch(1);
        Relay relay = relayOver(new MessageReader() {
            @Override public List<String> readMessage() {
                relayed.countDown();
                return List.of("a", "b");
            }
            @Override public boolean isReady() { return true; }
        });

        relay.initiate();
        try {
            Assertions.assertTrue(relayed.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            Health h = new RelayHealthIndicator(relay).health();
            Assertions.assertEquals(Status.UP, h.getStatus());
            Assertions.assertTrue(((Number) h.getDetails().get("relayed")).longValue() >= 2,
                    "relayed count should reflect messages forwarded");
        } finally {
            relay.teardown();
        }
    }
}
