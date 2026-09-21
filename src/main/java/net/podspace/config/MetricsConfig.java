package net.podspace.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the dimensions every metric needs to be comparable across deployments.
 *
 * <p>Without these, samples from an origin in one zone and an echo in another land in the same
 * series and cannot be told apart - which defeats the point of running them separately.
 *
 * <p><b>Why the instance tag is not called "instance".</b> Prometheus attaches its own `instance`
 * label naming the scrape target. A metric exposing that name collides, and with the default
 * honor_labels:false the scraper silently renames ours to `exported_instance` - so a dashboard
 * filtering on `instance` quietly selects the target rather than the pod, and matches everything.
 * Emitting `probe_instance` sidesteps the collision entirely. The configuration property stays
 * `myapp.instance`; only the emitted label differs.
 */
@Configuration
public class MetricsConfig {
    private static final Logger logger = LoggerFactory.getLogger(MetricsConfig.class);
    /** Deliberately not "instance" - see the class comment. */
    static final String INSTANCE_TAG = "probe_instance";

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${myapp.role:loopback}") String role,
            @Value("${myapp.az:unknown}") String az,
            // HOSTNAME is the pod name under Kubernetes; falls back when running locally.
            @Value("${myapp.instance:${HOSTNAME:unknown}}") String instance) {
        logger.info("Tagging all metrics with role={}, az={}, probe_instance={}", role, az, instance);
        return registry -> registry.config().commonTags(
                "role", role,
                "az", az,
                INSTANCE_TAG, instance);
    }
}
