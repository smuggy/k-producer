package net.podspace.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warns when more than one instance of this application is running.
 *
 * <p>End-to-end latency is derived from a timestamp taken when the message is created and another
 * taken when it is consumed. That subtraction is only meaningful while both happen on the same
 * clock, i.e. inside one JVM. Scaling out silently turns the measurement into "latency plus
 * whatever the clock skew between pods happens to be", with no error to notice - which for a
 * verification tool is worse than failing outright. Hence, the explicit warning.
 */
@Component
public class SingleInstanceGuard {
    private static final Logger logger = LoggerFactory.getLogger(SingleInstanceGuard.class);
    private final ObjectProvider<DiscoveryClient> discoveryClient;
    private final String applicationName;

    public SingleInstanceGuard(ObjectProvider<DiscoveryClient> discoveryClient,
                               @Value("${spring.application.name:k-producer}") String applicationName) {
        this.discoveryClient = discoveryClient;
        this.applicationName = applicationName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkInstanceCount() {
        logger.info("Measurement assumes a single instance: produce and consume timestamps are "
                + "taken from this JVM's clock, so latency is only valid when this process is both "
                + "the publisher and the consumer.");

        // Absent unless service discovery is enabled for the active profile, so this is a
        // best-effort check rather than a guarantee.
        DiscoveryClient client = discoveryClient.getIfAvailable();
        if (client == null) {
            logger.debug("No discovery client available; cannot check the instance count.");
            return;
        }

        try {
            int instances = client.getInstances(applicationName).size();
            if (instances > 1) {
                logger.warn("Discovered {} instances of '{}'. End-to-end latency will include the "
                                + "clock skew between them, and per-sequence delivery checks are not valid "
                                + "across instances, because each consumer sees only its assigned "
                                + "partitions. Run a single instance for measurement runs.",
                        instances, applicationName);
            } else if (instances == 1) {
                logger.info("Discovered a single instance of '{}', as expected.", applicationName);
            } else {
                // Registration is disabled or has not completed; absence of instances is not
                // evidence that this is the only one.
                logger.debug("Service discovery reported no registered instances of '{}'; "
                        + "cannot verify the instance count.", applicationName);
            }
        } catch (RuntimeException e) {
            // Never let a diagnostic check prevent start-up.
            logger.debug("Could not determine the instance count for '{}'.", applicationName, e);
        }
    }
}
