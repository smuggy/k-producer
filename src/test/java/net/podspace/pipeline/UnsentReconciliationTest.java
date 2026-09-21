package net.podspace.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageGenerator;
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
            return new Generated(null, "{}", ledger.nextSequence());
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
