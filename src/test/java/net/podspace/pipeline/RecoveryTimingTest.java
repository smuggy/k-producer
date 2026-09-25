package net.podspace.pipeline;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detecting an outage was never the gap - the failure counters already did that. How long it
 * lasted is the figure a broker upgrade or failover drill is judged on, and it has to span every
 * retry and backoff, not just the final attempt.
 */
class RecoveryTimingTest {

    @Test
    void anOutageIsTimedFromTheFirstFailureToTheRecovery() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Duration> reported = new AtomicReference<>();
        AtomicInteger reportedFailures = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);

        // Fails three times, so the measured outage must cover the 500ms and 1000ms backoffs
        // between attempts - not merely the last one.
        WorkerLoop loop = new WorkerLoop("timed", 50, () -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new IllegalStateException("broker is gone");
            }
            Thread.sleep(20);
        });
        loop.setRecoveryListener((name, outage, failures) -> {
            reported.set(outage);
            reportedFailures.set(failures);
            recovered.countDown();
        });

        loop.initiate();
        try {
            Assertions.assertTrue(recovered.await(15, TimeUnit.SECONDS), "never recovered");
        } finally {
            loop.teardown();
        }

        Assertions.assertEquals(3, reportedFailures.get());
        // 500 + 1000 + 2000 of backoff sits between the first failure and the success.
        Assertions.assertTrue(reported.get().toMillis() >= 1500,
                "outage must span the retries, got " + reported.get().toMillis() + "ms");
        Assertions.assertTrue(reported.get().toMillis() < 15_000, "implausibly long");
    }

    @Test
    void anOutageInProgressIsVisibleBeforeItEnds() throws Exception {
        // An outage that never ends emits no timer sample at all, so without this a total failure
        // would be indistinguishable from perfect health.
        CountDownLatch failed = new CountDownLatch(2);
        WorkerLoop loop = new WorkerLoop("ongoing", 50, () -> {
            failed.countDown();
            throw new IllegalStateException("still broken");
        });

        loop.initiate();
        try {
            Assertions.assertTrue(failed.await(10, TimeUnit.SECONDS));
            Thread.sleep(300);
            Duration current = loop.getCurrentOutage();
            Assertions.assertFalse(current.isZero(), "an in-progress outage must be observable");
            Assertions.assertTrue(current.toMillis() >= 200,
                    "should reflect elapsed time, got " + current.toMillis() + "ms");
        } finally {
            loop.teardown();
        }
    }

    @Test
    void aHealthyLoopReportsNoOutage() throws Exception {
        CountDownLatch ran = new CountDownLatch(2);
        WorkerLoop loop = new WorkerLoop("healthy", 50, () -> {
            ran.countDown();
            Thread.sleep(20);
        });
        loop.initiate();
        try {
            Assertions.assertTrue(ran.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals(Duration.ZERO, loop.getCurrentOutage());
        } finally {
            loop.teardown();
        }
    }

    @Test
    void theCurrentOutageResetsOnceRecovered() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        WorkerLoop loop = new WorkerLoop("resets", 50, () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("transient");
            }
            Thread.sleep(20);
        });
        loop.setRecoveryListener((n, d, f) -> recovered.countDown());

        loop.initiate();
        try {
            Assertions.assertTrue(recovered.await(10, TimeUnit.SECONDS));
            Thread.sleep(100);
            Assertions.assertEquals(Duration.ZERO, loop.getCurrentOutage(),
                    "a recovered loop is not still in an outage");
        } finally {
            loop.teardown();
        }
    }

    @Test
    void aSeparateOutageIsTimedSeparatelyRatherThanAccumulating() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch twoOutages = new CountDownLatch(2);
        AtomicReference<Duration> last = new AtomicReference<>();

        // fail, recover, fail, recover - the second outage must not include the healthy period.
        WorkerLoop loop = new WorkerLoop("twice", 50, () -> {
            int n = attempts.incrementAndGet();
            if (n == 1 || n == 3) {
                throw new IllegalStateException("blip");
            }
            Thread.sleep(20);
        });
        loop.setRecoveryListener((name, outage, failures) -> {
            last.set(outage);
            twoOutages.countDown();
        });

        loop.initiate();
        try {
            Assertions.assertTrue(twoOutages.await(20, TimeUnit.SECONDS), "expected two recoveries");
            Assertions.assertTrue(last.get().toMillis() < 5_000,
                    "the second outage must be timed on its own, got " + last.get().toMillis() + "ms");
        } finally {
            loop.teardown();
        }
    }

    @Test
    void aThrowingListenerDoesNotEndTheLoop() throws Exception {
        // Instrumentation must not become an outage of its own, during the outage it measures.
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch keptGoing = new CountDownLatch(3);
        WorkerLoop loop = new WorkerLoop("noisy", 50, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("one failure");
            }
            keptGoing.countDown();
            Thread.sleep(20);
        });
        loop.setRecoveryListener((n, d, f) -> {
            throw new IllegalStateException("listener is broken");
        });

        loop.initiate();
        try {
            Assertions.assertTrue(keptGoing.await(10, TimeUnit.SECONDS),
                    "a throwing listener must not stop the loop");
            Assertions.assertTrue(loop.isHealthy());
        } finally {
            loop.teardown();
        }
    }
}
