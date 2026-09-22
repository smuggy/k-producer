package net.podspace.pipeline;

import java.util.Map;

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
     * Extra, engine-specific values to include in the health report.
     *
     * <p>Empty by default, because the fields above are what every engine has in common and what
     * readiness is actually decided on. This exists so an engine can add the one number that makes
     * its own report diagnosable - the relay's forwarded count being the case in point - without
     * pushing a concept onto the other two that they have no meaning for.
     *
     * <p>These are reported, never used to decide up or down. A contributor that changed the
     * verdict from here would put the decision in two places.
     */
    default Map<String, Object> details() {
        return Map.of();
    }

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
