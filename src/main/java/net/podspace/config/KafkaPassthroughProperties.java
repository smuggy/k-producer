package net.podspace.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arbitrary Kafka client properties, merged verbatim into both the producer and the consumer.
 *
 * <p>Everything else in {@code AppConfig} names one setting at a time - bootstrap address, acks,
 * the two timeouts - which works until the cluster needs something the application has never heard
 * of. Security is the obvious case: TLS alone needs four or five properties, SASL needs more
 * again, and OAUTHBEARER needs a callback handler class. Enumerating them would mean a code change
 * for every mechanism, and this tool exists to probe clusters it does not own.
 *
 * <p>So rather than model security, this passes anything through:
 *
 * <pre>
 * myapp:
 *   kafka:
 *     properties:
 *       security.protocol: SSL
 *       ssl.truststore.type: PEM
 *       ssl.truststore.location: external-ca.pem
 * </pre>
 *
 * <p><b>These are applied last and therefore win</b> over the named settings above them. That is
 * deliberate - it is an escape hatch, and one that cannot override is not an escape hatch - but it
 * does mean {@code myapp.kafka.properties.acks} silently beats {@code myapp.kafka.acks}. The
 * effective values are logged at start-up so a surprise is visible rather than mysterious.
 *
 * <p>Note this deliberately does not reuse Spring Boot's {@code spring.kafka.*} namespace. Half
 * adopting it - Boot's properties for security, this application's for everything else - would
 * leave two mechanisms configuring one client, and no obvious answer to which applies.
 *
 * <p>Bound from the {@link Environment} on demand rather than as an {@code @ConfigurationProperties}
 * bean. That is not a style preference: {@code @ConfigurationProperties} beans are populated by a
 * bean post-processor, and {@code AppConfig}'s factory methods can run before it has been applied.
 * The injected object is then present but EMPTY, so the passthrough silently does nothing and the
 * client falls back to PLAINTEXT - which looks exactly like a configuration typo. Binding at the
 * point of use has no ordering to get wrong.
 */
final class KafkaPassthroughProperties {

    private static final String PREFIX = "myapp.kafka.properties";

    /** Keys are Kafka client property names, e.g. {@code security.protocol}. */
    private final Map<String, String> properties;

    private KafkaPassthroughProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    /** Reads the current values straight from the environment. */
    public static KafkaPassthroughProperties from(Environment environment) {
        Map<String, String> bound = Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, String.class))
                .orElseGet(LinkedHashMap::new);
        return new KafkaPassthroughProperties(new LinkedHashMap<>(bound));
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    /** True when anything is configured, so start-up logging can stay quiet in the common case. */
    public boolean isEmpty() {
        return properties.isEmpty();
    }

    /**
     * Property names whose values must never be logged. Matched as a substring so
     * {@code ssl.truststore.password} and {@code sasl.jaas.config} are both covered - the latter
     * embeds credentials inline.
     */
    private static final String[] SECRET_MARKERS = {"password", "secret", "jaas", "key"};

    /** The configured keys with secret values masked, safe to log. */
    public Map<String, String> masked() {
        Map<String, String> out = new LinkedHashMap<>();
        properties.forEach((k, v) -> out.put(k, isSecret(k) ? "****" : v));
        return out;
    }

    private static boolean isSecret(String key) {
        String lower = key.toLowerCase();
        for (String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
