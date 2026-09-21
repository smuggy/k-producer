package net.podspace.config;

import net.podspace.pipeline.EngineStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether one pipeline engine is actually doing its job.
 *
 * <p>Wired into the <em>readiness</em> group only, never liveness. An engine in {@code WorkerLoop}
 * backoff usually means the brokers are unreachable - the pod itself is fine. Failing liveness
 * there would have Kubernetes restart the pod repeatedly during exactly the outage this tool was
 * deployed to observe, destroying the measurement. Failing readiness instead takes the instance
 * out of service while leaving it running and reporting.
 *
 * <p><b>Not started is not unhealthy.</b> The publisher and watcher are started through the REST
 * endpoints rather than at boot, so a freshly rolled pod has both stopped. Reporting DOWN for that
 * would leave the pod permanently out of service and never able to accept the request that starts
 * it. An idle engine is reported UP, with {@code running: false} saying so plainly.
 *
 * <p>One class for all three engines: Publisher, Watcher and Relay fail in the same ways, and a
 * separate indicator each is how the duplicated lifecycle code started drifting before
 * {@code WorkerLoop} consolidated it.
 */
public class PipelineHealthIndicator implements HealthIndicator {
    private final EngineStatus engine;

    public PipelineHealthIndicator(EngineStatus engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        boolean running = engine.isRunning();
        // Two separate ways a running engine is unusable: the loop is erroring, or the inbound
        // side is not attached. Both matter - a Kafka poll against unreachable brokers returns
        // empty and throws nothing, so loop health alone would report UP while nothing flows.
        boolean up = !running || (engine.isHealthy() && engine.isAttached());

        Health.Builder builder = up ? Health.up() : Health.down();
        builder.withDetail("running", running)
                .withDetail("attached", engine.isAttached())
                .withDetail("totalFailures", engine.getTotalFailures());
        String lastFailure = engine.getLastFailure();
        if (lastFailure != null) {
            builder.withDetail("lastFailure", lastFailure);
        }
        return builder.build();
    }
}
