package net.podspace.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.messaging.MessageGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The publisher's rate and size are adjusted by a delta from request threads, and several requests
 * can overlap. These guard that an adjustment is atomic as a unit: the previous get-then-set pair
 * over volatile fields could have two callers read the same value and both write value+1, silently
 * dropping one of the two changes.
 */
class PublisherAdjustTest {

    /** Minimal generator - the filler size is the only part the publisher delegates. */
    private static class FakeGenerator implements MessageGenerator {
        private final AtomicInteger filler = new AtomicInteger();
        @Override public Generated createMessage() { return Generated.untracked("{}"); }
        @Override public void setFillerSize(int size) { filler.set(Math.max(0, size)); }
        @Override public int getFillerSize() { return filler.get(); }
        @Override public int adjustFillerSize(int delta) {
            return filler.updateAndGet(c -> Math.max(0, c + delta));
        }
    }

    private static Publisher publisher() {
        return new Publisher(new FakeGenerator(), m -> { },
                new DeliveryLedger(new SimpleMeterRegistry()));
    }

    /** Runs `threads` x `each` concurrent operations, returning once they have all finished. */
    private static void hammer(int threads, int each, Runnable op) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < each; i++) {
                            op.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // release them together, to maximise overlap
            Assertions.assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSleepAdjustmentsAreNotLost() throws Exception {
        Publisher p = publisher();
        long initial = p.getSleep();
        hammer(8, 500, () -> p.adjustSleep(1));

        Assertions.assertEquals(initial + 8 * 500, p.getSleep(),
                "every increment must be applied; a lost update means the read-modify-write raced");
    }

    @Test
    void concurrentMessageAdjustmentsAreNotLost() throws Exception {
        Publisher p = publisher();
        p.setMessages(10_000);
        hammer(8, 500, () -> p.adjustMessages(1));

        Assertions.assertEquals(10_000 + 8 * 500, p.getMessages());
    }

    @Test
    void concurrentFillerAdjustmentsAreNotLost() throws Exception {
        Publisher p = publisher();
        hammer(8, 500, () -> p.adjustFillerSize(1));

        Assertions.assertEquals(8 * 500, p.getFillerSize());
    }

    @Test
    void mixedRaiseAndLowerCancelOutExactly() throws Exception {
        Publisher p = publisher();
        p.setMessages(50_000);
        var pool = Executors.newFixedThreadPool(8);
        var done = new CountDownLatch(8);
        try {
            for (int t = 0; t < 8; t++) {
                final int delta = (t % 2 == 0) ? 5 : -5;
                pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        p.adjustMessages(delta);
                    }
                    done.countDown();
                });
            }
            Assertions.assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        Assertions.assertEquals(50_000, p.getMessages(),
                "equal numbers of raises and lowers must return to the starting value");
    }

    @Test
    void adjustmentsStillRespectTheFloors() {
        Publisher p = publisher();
        p.setMessages(3);
        Assertions.assertEquals(1, p.adjustMessages(-5), "batch size floors at one, never zero");
        p.setSleep(1);
        Assertions.assertEquals(1, p.adjustSleep(-1), "interval floors at one half-second");
        p.setFillerSize(100);
        Assertions.assertEquals(0, p.adjustFillerSize(-512), "filler floors at zero, never negative");
    }

    @Test
    void adjustReturnsTheValueItProduced() {
        Publisher p = publisher();
        p.setMessages(10);
        Assertions.assertEquals(15, p.adjustMessages(5));
        Assertions.assertEquals(15, p.getMessages());
    }
}
