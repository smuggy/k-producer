package net.podspace.pipeline;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the property the failure-testing use case depends on: an iteration that throws must not
 * end the loop. The tool has to keep running through the outages it exists to measure.
 */
class WorkerLoopTest {

    @Test
    void keepsRunningWhenEveryIterationThrows() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch thirdAttempt = new CountDownLatch(3);
        WorkerLoop loop = new WorkerLoop("always-failing", 50, () -> {
            attempts.incrementAndGet();
            thirdAttempt.countDown();
            throw new IllegalStateException("broker is gone");
        });

        loop.initiate();
        try {
            // 500ms then 1000ms of backoff, so three attempts land well inside this window.
            Assertions.assertTrue(thirdAttempt.await(5, TimeUnit.SECONDS),
                    "loop stopped retrying after a failure; it saw " + attempts.get() + " attempts");
            Assertions.assertFalse(loop.isHealthy(), "a continuously failing loop should not be healthy");
            Assertions.assertTrue(loop.getConsecutiveFailures() >= 3);
            Assertions.assertTrue(loop.getLastFailure().contains("broker is gone"));
        } finally {
            loop.teardown();
        }
    }

    @Test
    void recoversAndResetsFailureStateAfterATransientOutage() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        WorkerLoop loop = new WorkerLoop("transient", 50, () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("broker is gone");
            }
            succeeded.countDown();
            Thread.sleep(50); // stand in for a blocking read, so the loop does not spin
        });

        loop.initiate();
        try {
            Assertions.assertTrue(succeeded.await(5, TimeUnit.SECONDS), "loop never recovered");
            // Give the success path a moment to clear the counters.
            Thread.sleep(200);
            Assertions.assertTrue(loop.isHealthy(), "loop should be healthy again after a success");
            Assertions.assertEquals(0, loop.getConsecutiveFailures());
            Assertions.assertNull(loop.getLastFailure());
            // The failures still happened, and remain visible as a total.
            Assertions.assertEquals(2, loop.getTotalFailures());
        } finally {
            loop.teardown();
        }
    }

    @Test
    void teardownStopsAFailingLoopAndRunsTheExitHook() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger exitHookRuns = new AtomicInteger();
        WorkerLoop loop = new WorkerLoop("stoppable", 50,
                () -> {
                    failed.countDown();
                    throw new IllegalStateException("still broken");
                },
                exitHookRuns::incrementAndGet);

        loop.initiate();
        Assertions.assertTrue(failed.await(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        loop.teardown();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertTrue(elapsedMillis < 15_000,
                "teardown of a failing loop took " + elapsedMillis + "ms");
        Assertions.assertEquals(1, exitHookRuns.get(), "exit hook should run exactly once");
    }

    @Test
    void completesNormallyWhenNothingThrows() throws Exception {
        CountDownLatch ran = new CountDownLatch(2);
        WorkerLoop loop = new WorkerLoop("healthy", 50, () -> {
            ran.countDown();
            Thread.sleep(50);
        });

        loop.initiate();
        try {
            Assertions.assertTrue(ran.await(5, TimeUnit.SECONDS));
            Assertions.assertTrue(loop.isHealthy());
            Assertions.assertEquals(0, loop.getTotalFailures());
        } finally {
            loop.teardown();
        }
    }
}
