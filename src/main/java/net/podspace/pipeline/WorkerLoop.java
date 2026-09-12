package net.podspace.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The start/stop/pause machinery shared by {@link Publisher} and {@link Watcher}: a single worker
 * thread that repeatedly runs one task until asked to stop.
 *
 * <p>Both engines previously carried their own copy of this, which drifted - the same concurrency
 * fixes had to be applied twice and one copy was missing an early return. Owning it here means a
 * fix lands once.
 */
public final class WorkerLoop {
    private static final Logger logger = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    /** One pass of the loop. Allowed to throw: the loop logs and exits rather than dying silently. */
    @FunctionalInterface
    public interface Task {
        void runOnce() throws Exception;
    }

    private final String name;
    private final long pauseMillis;
    private final Task task;
    private final Runnable onExit;

    // Written by request threads, read by the worker thread; volatile supplies the happens-before
    // edge so that stop and pause are actually observed by the running loop.
    private volatile boolean quit;
    private volatile boolean pause;
    // Guarded by the synchronized lifecycle methods, which serialize the check-then-act on
    // `started` and safely publish `pool` between the starting and stopping request threads.
    private boolean started;
    private ExecutorService pool;

    public WorkerLoop(String name, long pauseMillis, Task task) {
        this(name, pauseMillis, task, () -> { });
    }

    /**
     * @param onExit run once when the loop finishes, however it finishes.
     */
    public WorkerLoop(String name, long pauseMillis, Task task, Runnable onExit) {
        this.name = name;
        this.pauseMillis = pauseMillis;
        this.task = task;
        this.onExit = onExit;
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
                task.runOnce();
            }
        } catch (Exception e) {
            logger.error("{}: loop terminated by exception", name, e);
        } finally {
            onExit.run();
            logger.info("{}: loop done.", name);
        }
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
