package net.podspace.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The start/stop/pause machinery shared by {@link Publisher} and {@link Watcher}: a single worker
 * thread that repeatedly runs one task until asked to stop.
 *
 * <p>Both engines previously carried their own copy of this, which drifted - the same concurrency
 * fixes had to be applied twice and one copy was missing an early return. Owning it here means a
 * fix lands once.
 */
// Package-private on purpose: this is the pipeline's internal engine. Publisher,
// Watcher and Relay are the faces the rest of the application uses, and they expose
// only what callers need. Widening this later is easy; narrowing it would not be.
final class WorkerLoop {
    private static final Logger logger = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 10;
    private static final long INITIAL_BACKOFF_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    /**
     * One pass of the loop. Allowed to throw: the loop records the failure, backs off and tries
     * again. A failing iteration must never end the loop - this tool is expected to keep running
     * through the infrastructure outages it exists to measure.
     */
    @FunctionalInterface
    public interface Task {
        void runOnce() throws Exception;
    }

    /**
     * Notified when a run of consecutive failures ends in a success.
     *
     * <p>Detecting an outage is only half of what the failure-testing use case needs; the other
     * half is how long it lasted, which is the figure a broker upgrade or a failover test is
     * actually judged on. This is a callback rather than a metric registered here so that
     * WorkerLoop stays free of Micrometer - the engines own their instrumentation.
     *
     * <p>Called on the worker thread immediately before it resumes work, so it must be cheap and
     * must not throw.
     */
    @FunctionalInterface
    public interface RecoveryListener {
        void recovered(String loopName, Duration outage, int consecutiveFailures);
    }

    /** Registers the listener notified when this loop recovers. Replaces any previous one. */
    public void setRecoveryListener(RecoveryListener listener) {
        this.recoveryListener = listener == null
                ? (name, duration, failures) -> { }
                : listener;
    }

    private final String name;
    private final long pauseMillis;
    private final Task task;

    // Written by request threads, read by the worker thread; volatile supplies the happens-before
    // edge so that stop and pause are actually observed by the running loop.
    private volatile boolean quit;
    private volatile boolean pause;
    // Failure state, written by the worker thread and read by anything reporting health.
    private final AtomicLong totalFailures = new AtomicLong();
    private volatile int consecutiveFailures;
    private volatile String lastFailure;
    /** Nanotime of the first failure in the current run of failures; 0 while healthy. */
    private volatile long outageStartNanos;
    /** Notified when a run of failures ends. Never null, so the loop needs no null check. */
    private volatile RecoveryListener recoveryListener = (name, duration, failures) -> { };
    // Guarded by the synchronized lifecycle methods, which serialize the check-then-act on
    // `started` and safely publish `pool` between the starting and stopping request threads.
    private boolean started;
    private ExecutorService pool;

    // Deliberately no exit hook. There was one, used by Watcher to close its reader when the loop
    // stopped - which broke restart, because the reader is a container-managed singleton that
    // outlives any one start/stop cycle. Resources whose lifetime exceeds the loop's should be
    // closed by whoever owns them, not from here.
    public WorkerLoop(String name, long pauseMillis, Task task) {
        this.name = name;
        this.pauseMillis = pauseMillis;
        this.task = task;
    }

    public void pause() {
        pause = true;
    }

    public void resume() {
        pause = false;
    }

    public synchronized void initiate() {
        if (started) {
            logger.info("{}: already started... leaving", name);
            return;
        }

        started = true;
        quit = false;
        pause = false;
        logger.info("{}: starting thread pool", name);
        pool = Executors.newFixedThreadPool(1);
        pool.submit(this::run);
    }

    public synchronized void teardown() {
        try {
            quit = true;
            if (!started) {
                logger.info("{}: teardown - not started, leaving", name);
                return;
            }
            if (pool == null) {
                logger.info("{}: teardown - pool null", name);
                return;
            }
            logger.info("{}: teardown - shutting down", name);
            // Escalate rather than waiting forever: shutdown() never interrupts, and close()
            // blocks indefinitely, so a worker parked in a blocking read or write would wedge the
            // calling request thread permanently.
            pool.shutdown();
            if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.info("{}: teardown - worker still running, interrupting it.", name);
                pool.shutdownNow();
                if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("{}: teardown - worker did not respond to interrupt.", name);
                }
            }

            quit = false;
            started = false;
            pause = false;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        logger.info("{}: teardown - worker thread shut down.", name);
    }

    private void run() {
        logger.info("{}: loop starting...", name);
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        try {
            // The interrupt check makes shutdownNow() effective even before quit is observed.
            while (!quit && !Thread.currentThread().isInterrupted()) {
                if (pause) {
                    logger.info("{}: paused...", name);
                    if (!sleepFor(pauseMillis)) {
                        return;
                    }
                    continue;
                }

                try {
                    task.runOnce();
                    backoffMillis = noteSuccess(backoffMillis);
                } catch (InterruptedException e) {
                    // teardown() escalated to shutdownNow(); stop rather than retry.
                    Thread.currentThread().interrupt();
                    logger.info("{}: interrupted, stopping.", name);
                    return;
                } catch (Exception e) {
                    // Catching inside the loop is the point: a broker going away must not end the
                    // run, or the tool dies during the very outage it is measuring.
                    noteFailure(e, backoffMillis);
                    if (!sleepFor(backoffMillis)) {
                        return;
                    }
                    backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
                }
            }
        } finally {
            logger.info("{}: loop done.", name);
        }
    }

    /** Resets the failure state and reports how long the outage lasted. */
    private long noteSuccess(long backoffMillis) {
        if (consecutiveFailures > 0) {
            // Measured from the FIRST failure, not the last: the question being answered is how
            // long the pipeline was unable to work, which spans every retry and backoff in
            // between. Nanotime, so it is unaffected by wall-clock adjustment mid-outage.
            Duration outage = Duration.ofNanos(System.nanoTime() - outageStartNanos);
            int failures = consecutiveFailures;
            logger.info("{}: recovered after {} consecutive failures in {} ms (last: {}).",
                    name, failures, outage.toMillis(), lastFailure);
            consecutiveFailures = 0;
            lastFailure = null;
            outageStartNanos = 0;
            try {
                recoveryListener.recovered(name, outage, failures);
            } catch (RuntimeException e) {
                // A listener must never end the loop - that would turn instrumentation into an
                // outage of its own, during the outage it is there to measure.
                logger.warn("{}: recovery listener threw; continuing.", name, e);
            }
            return INITIAL_BACKOFF_MILLIS;
        }
        return backoffMillis;
    }

    private void noteFailure(Exception e, long backoffMillis) {
        totalFailures.incrementAndGet();
        if (consecutiveFailures == 0) {
            outageStartNanos = System.nanoTime();   // first failure of a new outage
        }
        consecutiveFailures++;
        lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
        logger.error("{}: iteration failed ({} consecutive), retrying in {}ms",
                name, consecutiveFailures, backoffMillis, e);
    }

    /** Total failed iterations since this loop object was created. */
    public long getTotalFailures() {
        return totalFailures.get();
    }

    /** Failed iterations since the last success; 0 while healthy. */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** Description of the most recent failure, or null while healthy. */
    public String getLastFailure() {
        return lastFailure;
    }

    public boolean isHealthy() {
        return consecutiveFailures == 0;
    }

    /**
     * How long the current outage has been running, or zero while healthy. Lets a health check or
     * dashboard show an outage in progress rather than only completed ones, which otherwise stay
     * invisible until they end.
     */
    public Duration getCurrentOutage() {
        long started = outageStartNanos;
        return started == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - started);
    }

    /**
     * Whether the loop has been started and not torn down. Synchronized to match the lifecycle
     * methods that own this flag. An engine that was never started is idle, not broken - health
     * reporting needs to tell those apart.
     */
    public synchronized boolean isRunning() {
        return started;
    }

    /**
     * Sleeps for the given time, returning false if the thread was interrupted - which, given
     * teardown()'s escalation, means "stop now".
     */
    public static boolean sleepFor(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
