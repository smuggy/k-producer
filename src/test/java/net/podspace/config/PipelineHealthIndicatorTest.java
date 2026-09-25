package net.podspace.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.MessageGenerator.Generated;
import net.podspace.pipeline.EngineStatus;
import net.podspace.pipeline.Publisher;
import net.podspace.pipeline.Relay;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers the readiness contributor shared by all three pipeline engines. The property that matters
 * is the liveness/readiness split: an engine that cannot reach its brokers must report DOWN for
 * readiness while the process stays alive, so Kubernetes pulls it out of service rather than
 * restarting it during the very outage the tool was deployed to observe.
 */
class PipelineHealthIndicatorTest {

    /** Direct control over every input, so each combination can be asserted exactly. */
    private record FakeEngine(boolean running, boolean healthy, boolean attached,
                              long totalFailures, String lastFailure) implements EngineStatus {
        @Override public boolean isRunning() { return running; }
        @Override public boolean isHealthy() { return healthy; }
        @Override public boolean isAttached() { return attached; }
        @Override public long getTotalFailures() { return totalFailures; }
        @Override public String getLastFailure() { return lastFailure; }
    }

    private static byte[] b(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Status statusOf(EngineStatus e) {
        return new PipelineHealthIndicator(e).health().getStatus();
    }

    @Test
    void anEngineThatWasNeverStartedIsUpBecauseItIsIdleNotBroken() {
        // The publisher and watcher start through REST, so a freshly rolled pod has both stopped.
        // Reporting DOWN would leave it permanently out of service, unable to receive the very
        // request that starts it.
        Assertions.assertEquals(Status.UP,
                statusOf(new FakeEngine(false, false, false, 0, null)),
                "a stopped engine must not fail readiness");
    }

    @Test
    void aRunningAttachedHealthyEngineIsUp() {
        Assertions.assertEquals(Status.UP, statusOf(new FakeEngine(true, true, true, 0, null)));
    }

    @Test
    void aRunningEngineWithNoAssignmentIsDown() {
        Assertions.assertEquals(Status.DOWN,
                statusOf(new FakeEngine(true, true, false, 0, null)),
                "attached=false means nothing can arrive, so the instance is not ready");
    }

    @Test
    void aRunningEngineInBackoffIsDown() {
        Assertions.assertEquals(Status.DOWN,
                statusOf(new FakeEngine(true, false, true, 7, "TimeoutException: boom")));
    }

    @Test
    void detailsCarryEnoughToDiagnoseWithoutTheLogs() {
        Health h = new PipelineHealthIndicator(
                new FakeEngine(true, false, false, 7, "TimeoutException: boom")).health();
        Assertions.assertEquals(true, h.getDetails().get("running"));
        Assertions.assertEquals(false, h.getDetails().get("attached"));
        Assertions.assertEquals(7L, h.getDetails().get("totalFailures"));
        Assertions.assertEquals("TimeoutException: boom", h.getDetails().get("lastFailure"));
    }

    @Test
    void lastFailureIsOmittedWhileHealthy() {
        Health h = new PipelineHealthIndicator(new FakeEngine(true, true, true, 0, null)).health();
        Assertions.assertFalse(h.getDetails().containsKey("lastFailure"));
    }

    // --- against the real engines, not just the fake -----------------------------------------

    @Test
    void aPublisherIsNeverReportedDetached() {
        // A publisher only writes; there is no inbound side that could be missing, and treating it
        // as detached would make every publisher permanently not-ready.
        Publisher p = new Publisher(new net.podspace.messaging.MessageGenerator() {
            @Override public Generated createMessage() { return Generated.untracked("{}"); }
            @Override public void setFillerSize(int size) { }
            @Override public int getFillerSize() { return 0; }
            @Override public int adjustFillerSize(int delta) { return 0; }
        }, m -> { }, new net.podspace.pipeline.DeliveryLedger(new SimpleMeterRegistry()));
        Assertions.assertTrue(p.isAttached());
        Assertions.assertFalse(p.isRunning(), "not started yet");
        Assertions.assertEquals(Status.UP, statusOf(p));
    }

    @Test
    void aRunningRelayWhoseReadsKeepThrowingGoesDown() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        Relay relay = new Relay(new MessageReader() {
            @Override public List<byte[]> readMessage() {
                failed.countDown();
                throw new IllegalStateException("broker is gone");
            }
            @Override public boolean isReady() { return true; }
        }, m -> { }, new SimpleMeterRegistry());

        relay.initiate();
        try {
            Assertions.assertTrue(failed.await(5, TimeUnit.SECONDS));
            Status s = null;
            for (int i = 0; i < 50 && s != Status.DOWN; i++) {
                Thread.sleep(20);
                s = statusOf(relay);
            }
            Assertions.assertEquals(Status.DOWN, s);
            Assertions.assertTrue(relay.isRunning());
        } finally {
            relay.teardown();
        }
        // Once stopped it is idle again, so readiness recovers rather than latching DOWN.
        Assertions.assertEquals(Status.UP, statusOf(relay), "a torn-down engine is idle, not broken");
    }

    @Test
    void aRelayReportsWhatItHasForwarded() throws Exception {
        CountDownLatch relayed = new CountDownLatch(1);
        Relay relay = new Relay(new MessageReader() {
            @Override public List<byte[]> readMessage() {
                if (relayed.getCount() == 0) {
                    return Collections.emptyList();
                }
                relayed.countDown();
                return List.of(b("a"), b("b"), b("c"));
            }
            @Override public boolean isReady() { return true; }
        }, m -> { }, new SimpleMeterRegistry());

        relay.initiate();
        try {
            Assertions.assertTrue(relayed.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            Health h = new PipelineHealthIndicator(relay).health();
            Assertions.assertEquals(3L, ((Number) h.getDetails().get("relayed")).longValue(),
                    "the forwarded count belongs on the health report, not only in the metric");
        } finally {
            relay.teardown();
        }
    }

    @Test
    void enginesWithNothingExtraToSayAddNoDetails() {
        // Publisher and watcher have no equivalent of a forwarded count; the default is empty
        // rather than a placeholder that would have to mean something.
        Health h = new PipelineHealthIndicator(new FakeEngine(true, true, true, 0, null)).health();
        Assertions.assertEquals(Set.of("running", "attached", "totalFailures"),
                h.getDetails().keySet());
    }

    @Test
    void engineDetailsAreReportedButCannotChangeTheVerdict() {
        // A contributor able to flip up/down from its details would put the decision in two places.
        EngineStatus noisy = new EngineStatus() {
            @Override public boolean isRunning() { return true; }
            @Override public boolean isHealthy() { return false; }
            @Override public boolean isAttached() { return true; }
            @Override public long getTotalFailures() { return 4; }
            @Override public String getLastFailure() { return "boom"; }
            @Override public Map<String, Object> details() { return Map.of("status", "UP", "fine", true); }
        };
        Health h = new PipelineHealthIndicator(noisy).health();
        Assertions.assertEquals(Status.DOWN, h.getStatus(),
                "an unhealthy engine stays DOWN whatever its details claim");
        Assertions.assertEquals("UP", h.getDetails().get("status"));
    }

    @Test
    void aRelayWithNoAssignmentIsDownWhileRunning() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        Relay relay = new Relay(new MessageReader() {
            @Override public List<byte[]> readMessage() {
                polls.incrementAndGet();
                return Collections.emptyList();
            }
            @Override public boolean isReady() { return false; }
        }, m -> { }, new SimpleMeterRegistry());

        relay.initiate();
        try {
            Assertions.assertEquals(Status.DOWN, statusOf(relay));
        } finally {
            relay.teardown();
        }
    }
}
