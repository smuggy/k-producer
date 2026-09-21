package net.podspace.pipeline;

/**
 * The health surface every pipeline engine exposes, so one indicator can report on any of them.
 *
 * <p>Publisher, Watcher and Relay all wrap a {@link WorkerLoop} and fail in the same ways, so
 * reporting them through one interface keeps a single definition of what "ready" means instead of
 * one per engine that drifts apart - which is exactly what happened to the lifecycle code before
 * WorkerLoop existed.
 */
public interface EngineStatus {

    /** Started and not torn down. A stopped engine is idle, not broken. */
    boolean isRunning();

    /** No consecutive failures; false while the loop is backing off after an error. */
    boolean isHealthy();

    /**
     * Whether the inbound side is actually attached. Always true for engines that only write:
     * a publisher has nothing to be attached to.
     */
    boolean isAttached();

    long getTotalFailures();

    /** Description of the most recent failure, or null while healthy. */
    String getLastFailure();

    /**
     * A status for an engine this role does not run at all.
     *
     * <p>Lets every contributor be registered in every role, which is what allows Spring's
     * health-group membership validation to be switched on: a group listing a contributor that
     * only exists in some roles would otherwise fail startup everywhere else. Reporting it as not
     * running is the same thing the publisher and watcher already report in the echo role, so the
     * three stay symmetric.
     */
    static EngineStatus idle() {
        return IDLE;
    }

    EngineStatus IDLE = new EngineStatus() {
        @Override public boolean isRunning() { return false; }
        @Override public boolean isHealthy() { return true; }
        @Override public boolean isAttached() { return true; }
        @Override public long getTotalFailures() { return 0; }
        @Override public String getLastFailure() { return null; }
    };
}
