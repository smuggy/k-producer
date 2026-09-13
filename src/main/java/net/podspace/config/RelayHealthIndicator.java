package net.podspace.config;

import net.podspace.pipeline.Relay;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reports whether the echo relay is actually forwarding.
 *
 * <p>Wired into the <em>readiness</em> group only, never liveness. A relay stuck in
 * {@code WorkerLoop} backoff usually means the brokers are unreachable - the pod itself is fine.
 * Failing liveness there would have Kubernetes restart the pod repeatedly during exactly the
 * outage this tool was deployed to observe, destroying the measurement. Failing readiness instead
 * takes the instance out of service while leaving it running and reporting.
 */
// Bean name must NOT be "relay": that collides with the Relay bean itself. Left as the default
// (relayHealthIndicator), from which Spring derives the health contributor id "relay" - the key
// the readiness group references.
@Component
@ConditionalOnProperty(name = "myapp.role", havingValue = "echo")
public class RelayHealthIndicator implements HealthIndicator {
    private final Relay relay;

    public RelayHealthIndicator(Relay relay) {
        this.relay = relay;
    }

    @Override
    public Health health() {
        // Two separate ways to be unusable: the loop is erroring, or the inbound side is not
        // attached at all. A Kafka poll against unreachable brokers throws nothing and simply
        // returns empty, so loop health alone would report UP while nothing is being consumed.
        boolean up = relay.isHealthy() && relay.isReaderReady();
        Health.Builder builder = up ? Health.up() : Health.down();
        builder.withDetail("relayed", relay.getRelayedCount())
                .withDetail("readerAttached", relay.isReaderReady())
                .withDetail("totalFailures", relay.getTotalFailures());
        String lastFailure = relay.getLastFailure();
        if (lastFailure != null) {
            builder.withDetail("lastFailure", lastFailure);
        }
        return builder.build();
    }
}
