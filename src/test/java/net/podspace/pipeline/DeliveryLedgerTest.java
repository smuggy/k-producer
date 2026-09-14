package net.podspace.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryLedgerTest {
    private DeliveryLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new DeliveryLedger(new SimpleMeterRegistry());
    }

    /** Publishes n messages, returning the sequences the ledger issued. */
    private long[] produce(int n) {
        long[] seqs = new long[n];
        for (int i = 0; i < n; i++) {
            seqs[i] = ledger.nextSequence();
        }
        return seqs;
    }

    private void receiveAll(long[] seqs) {
        for (long s : seqs) {
            ledger.received(ledger.getRunId(), s);
        }
    }

    @Test
    void reportsNoLossWhenEverythingArrives() {
        receiveAll(produce(100));
        ledger.finalizeOutstanding();

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(100, s.produced());
        Assertions.assertEquals(100, s.received());
        Assertions.assertEquals(0, s.missing(), "nothing should be reported lost");
        Assertions.assertEquals(0, s.pending());
        Assertions.assertEquals(0, s.duplicates());
    }

    @Test
    void countsAGapAsMissingOnlyOnceSettled() {
        long[] seqs = produce(10);
        for (long s : seqs) {
            if (s != 4) {
                ledger.received(ledger.getRunId(), s);
            }
        }

        // Before settling the gap is pending, not lost - it could still be in flight.
        DeliveryLedger.Snapshot before = ledger.snapshot();
        Assertions.assertEquals(0, before.missing(), "a gap must not be called loss prematurely");
        Assertions.assertEquals(1, before.pending());

        ledger.finalizeOutstanding();
        DeliveryLedger.Snapshot after = ledger.snapshot();
        Assertions.assertEquals(1, after.missing());
        Assertions.assertEquals(0, after.pending());
        Assertions.assertEquals(9, after.received());
    }

    @Test
    void detectsDuplicatesWithoutInflatingReceived() {
        long[] seqs = produce(5);
        receiveAll(seqs);
        ledger.received(ledger.getRunId(), seqs[2]);
        ledger.received(ledger.getRunId(), seqs[2]);

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(2, s.duplicates());
        Assertions.assertEquals(5, s.received(), "duplicates must not count as new arrivals");
        ledger.finalizeOutstanding();
        Assertions.assertEquals(0, ledger.snapshot().missing());
    }

    @Test
    void detectsOutOfOrderArrival() {
        long[] seqs = produce(5);
        ledger.received(ledger.getRunId(), seqs[0]);
        ledger.received(ledger.getRunId(), seqs[3]);
        ledger.received(ledger.getRunId(), seqs[1]); // below the high-water mark
        ledger.received(ledger.getRunId(), seqs[2]);
        ledger.received(ledger.getRunId(), seqs[4]);

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(2, s.outOfOrder());
        Assertions.assertEquals(5, s.received());
        ledger.finalizeOutstanding();
        Assertions.assertEquals(0, ledger.snapshot().missing(), "reordering is not loss");
    }

    @Test
    void ignoresMessagesFromAnotherRun() {
        long[] seqs = produce(3);
        receiveAll(seqs);
        // Backlog left on the topic by a previous run must not read as reordering or duplication.
        ledger.received("some-other-run", 99);
        ledger.received("some-other-run", 1);

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(2, s.foreign());
        Assertions.assertEquals(3, s.received());
        Assertions.assertEquals(0, s.duplicates());
        Assertions.assertEquals(0, s.outOfOrder());
    }

    @Test
    void settlesOldSequencesOnceTheWindowSlidesPastThem() {
        // One short, then push far enough beyond the window that the gap must be judged.
        int total = DeliveryLedger.WINDOW + 10;
        long[] seqs = produce(total);
        for (long s : seqs) {
            if (s != 0) {
                ledger.received(ledger.getRunId(), s);
            }
        }

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(1, s.missing(), "sequence 0 should have been settled by the slide");
        Assertions.assertEquals(total - 1, s.received());
    }

    @Test
    void countsAnArrivalAfterSettlingAsLateRatherThanNew() {
        int total = DeliveryLedger.WINDOW + 10;
        long[] seqs = produce(total);
        for (long s : seqs) {
            if (s != 0) {
                ledger.received(ledger.getRunId(), s);
            }
        }
        Assertions.assertEquals(1, ledger.snapshot().missing());

        // The straggler finally turns up, long after it was written off.
        ledger.received(ledger.getRunId(), 0);

        DeliveryLedger.Snapshot s = ledger.snapshot();
        Assertions.assertEquals(1, s.late());
        Assertions.assertEquals(total - 1, s.received(), "a late arrival is not a new one");
    }

    @Test
    void sequencesAreUniqueUnderConcurrentPublishers() throws Exception {
        int threads = 4;
        int perThread = 5_000;
        var seen = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var latch = new java.util.concurrent.CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        seen.add(ledger.nextSequence());
                    }
                    latch.countDown();
                });
            }
            Assertions.assertTrue(latch.await(20, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        Assertions.assertEquals(threads * perThread, seen.size(), "sequences must never be reused");
        Assertions.assertEquals(threads * perThread, ledger.snapshot().produced());
    }
}
