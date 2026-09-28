package net.podspace.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageGenerator;
import net.podspace.messaging.MessageWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A send that fails locally was never the cluster's to lose.
 *
 * <p>The sequence is issued when the message is created, before the send is attempted, so a send
 * that throws used to leave its sequence outstanding forever: pending climbed without bound and
 * the window eventually reported it as missing - loss the cluster never caused. Observed live as
 * 32 phantom pending in Kubernetes and 50 against a remote cluster.
 */
class UnsentReconciliationTest {

    /** A generator wired to a real ledger, so sequences behave as they do in production. */
    private static class Gen implements MessageGenerator {
        private final DeliveryLedger ledger;
        Gen(DeliveryLedger ledger) { this.ledger = ledger; }
        @Override public Generated createMessage() {
            return new Generated(null, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), ledger.nextSequence());
        }
        @Override public void setFillerSize(int size) { }
        @Override public int getFillerSize() { return 0; }
        @Override public int adjustFillerSize(int delta) { return 0; }
    }

    @Test
    void aFailedSendIsRetiredRatherThanLeftPending() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        long seq = ledger.nextSequence();

        Assertions.assertEquals(1, ledger.snapshot().pending(), "issued, not yet accounted for");

        ledger.sendFailed(seq);

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(0, s.pending(), "a message that never left must not sit in flight");
        Assertions.assertEquals(1, s.unsent());
        Assertions.assertEquals(1, s.produced());
        Assertions.assertEquals(0, s.offered(), "the cluster was never given anything");
        Assertions.assertEquals(0, s.received());
    }

    @Test
    void retiredSequencesAreNeverLaterReportedAsMissing() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        for (int i = 0; i < 50; i++) {
            ledger.sendFailed(ledger.nextSequence());
        }
        ledger.finalizeOutstanding();

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(0, s.missing(),
                "a total send failure is a producer fault, not cluster loss");
        Assertions.assertEquals(50, s.unsent());
        Assertions.assertEquals(0, s.pending());
    }

    @Test
    void realLossIsStillReportedAlongsideUnsent() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        long a = ledger.nextSequence();   // sent, arrives
        long b = ledger.nextSequence();   // sent, lost by the cluster
        long c = ledger.nextSequence();   // never sent

        ledger.received(ledger.getRunId(), a);
        ledger.sendFailed(c);
        ledger.finalizeOutstanding();

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(3, s.produced());
        Assertions.assertEquals(1, s.unsent());
        Assertions.assertEquals(2, s.offered(), "two were actually handed to the cluster");
        Assertions.assertEquals(1, s.received());
        Assertions.assertEquals(1, s.missing(), "only the one the cluster was given and lost");
        Assertions.assertEquals(s.offered(), s.received() + s.missing(),
                "everything offered is either received or missing");
    }

    @Test
    void retiringTwiceCountsOnce() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        long seq = ledger.nextSequence();
        ledger.sendFailed(seq);
        ledger.sendFailed(seq);
        Assertions.assertEquals(1, ledger.snapshot().unsent());
    }

    @Test
    void anUntrackedSequenceIsIgnored() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        ledger.sendFailed(MessageGenerator.Generated.NO_SEQUENCE);
        Assertions.assertEquals(0, ledger.snapshot().unsent());
    }

    // --- through the publisher, which is where the correlation has to hold -------------------

    @Test
    void anAsynchronousSendFailureAlsoRetiresItsSequence() {
        // The case a synchronous-only fix misses, and the one that actually happens in production:
        // brokers reachable but unable to satisfy min.insync.replicas. The record buffers fine, so
        // writeMessage returns normally, and the rejection arrives later. Measured against a real
        // cluster with 2 of 3 brokers down, this produced 4510 messages reported as LOST with
        // unsent stuck at 0 - the ledger blaming the cluster for messages it had explicitly refused.
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        AtomicInteger accepted = new AtomicInteger();

        // Accepts every write, then fails it - exactly what KafkaWriter does via whenComplete.
        MessageWriter acceptsThenFails = new MessageWriter() {
            @Override public void writeMessage(byte[] message) { }
            @Override public void writeMessage(String key, byte[] message, Runnable onAsyncFailure) {
                accepted.incrementAndGet();
                onAsyncFailure.run();
            }
        };

        Publisher publisher = new Publisher(new Gen(ledger), acceptsThenFails, ledger);
        publisher.setMessages(4);
        publisher.publishOnce();

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(4, accepted.get(), "the writer accepted every message");
        Assertions.assertEquals(4, s.produced());
        Assertions.assertEquals(4, s.unsent(), "an async rejection must retire the sequence");
        Assertions.assertEquals(0, s.offered(), "the cluster kept none of them");
        Assertions.assertEquals(0, s.pending(), "nothing may be left in flight");

        ledger.finalizeOutstanding();
        Assertions.assertEquals(0, ledger.snapshot().missing(),
                "a cluster that refused the writes did not lose them");
    }

    @Test
    void aSuccessfulAsyncSendLeavesTheSequenceInFlight() {
        // The mirror image: the callback must not fire on success, or every delivered message
        // would be retired as unsent and reconciliation would under-count what the cluster took.
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        MessageWriter accepts = new MessageWriter() {
            @Override public void writeMessage(byte[] message) { }
            @Override public void writeMessage(String key, byte[] message, Runnable onAsyncFailure) {
                // no failure reported
            }
        };
        Publisher publisher = new Publisher(new Gen(ledger), accepts, ledger);
        publisher.setMessages(3);
        publisher.publishOnce();

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(0, s.unsent());
        Assertions.assertEquals(3, s.offered(), "the cluster was given all three");
        Assertions.assertEquals(3, s.pending(), "still in flight until they come back");
    }

    @Test
    void aTransportWithNoAsyncPhaseIsUnaffected() {
        // The queue and no-op writers do not override the callback overload; the default must
        // simply write, not silently retire everything as unsent.
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        AtomicInteger written = new AtomicInteger();
        Publisher publisher = new Publisher(new Gen(ledger),
                m -> written.incrementAndGet(), ledger);
        publisher.setMessages(3);
        publisher.publishOnce();

        Assertions.assertEquals(3, written.get());
        Assertions.assertEquals(0, ledger.snapshot().unsent(),
                "a plain writer reports no async failure, so nothing is retired");
    }

    @Test
    void thePublisherRetiresTheSequenceOfTheMessageThatFailed() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        AtomicBoolean broken = new AtomicBoolean(false);
        AtomicInteger written = new AtomicInteger();

        Publisher publisher = new Publisher(new Gen(ledger), payload -> {
            if (broken.get()) {
                throw new IllegalStateException("broker is gone");
            }
            written.incrementAndGet();
        }, ledger);
        publisher.setMessages(1);

        // One good publish, then the brokers go away for the next three.
        publisher.publishOnce();
        broken.set(true);
        for (int i = 0; i < 3; i++) {
            Assertions.assertThrows(IllegalStateException.class, publisher::publishOnce,
                    "the failure must still reach the loop so it backs off");
        }

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(1, written.get());
        Assertions.assertEquals(4, s.produced());
        Assertions.assertEquals(3, s.unsent(), "each failed send retires its own sequence");
        Assertions.assertEquals(1, s.offered());
        Assertions.assertEquals(1, s.pending(), "only the one actually sent is still in flight");
    }
}
