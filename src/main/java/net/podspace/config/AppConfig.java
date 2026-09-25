package net.podspace.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.podspace.pipeline.Watcher;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import net.podspace.domain.codec.AvroTemperatureCodec;
import net.podspace.domain.codec.JsonTemperatureCodec;
import net.podspace.domain.codec.TemperatureCodec;
import net.podspace.domain.TemperatureGenerator;
import net.podspace.management.MBeanContainer;
import net.podspace.management.ManagementAgent;
import net.podspace.management.ManagementAgentImpl;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.MessageWriter;
import net.podspace.messaging.kafka.KafkaReader;
import net.podspace.messaging.kafka.KafkaWriter;
import net.podspace.messaging.noop.ConsoleWriter;
import net.podspace.messaging.noop.EmptyReader;
import net.podspace.messaging.noop.EmptyWriter;
import net.podspace.messaging.queue.QueueManager;
import net.podspace.pipeline.DeliveryLedger;
import net.podspace.pipeline.Publisher;
import net.podspace.pipeline.EngineStatus;
import net.podspace.pipeline.Relay;
import net.podspace.pipeline.PublisherManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import jakarta.annotation.PostConstruct;
import javax.management.NotCompliantMBeanException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class AppConfig {
    private static final String LOOPBACK = "loopback";
    private static final String ORIGIN = "origin";
    private static final String ECHO = "echo";
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class.getName());

    @Value("${myapp.kafka.topicName}")
    private String topicName;//="test-topic-one";
    /** Return topic for echo round trips. Required for the origin and echo roles, unused otherwise. */
    @Value("${myapp.kafka.echoTopicName:}")
    private String echoTopicName;
    /**
     * loopback - publish and consume the same topic in one process (default, and the only role
     * whose latency figure is one-way).
     * origin   - publish to topicName, consume echoTopicName, and measure the full round trip.
     * echo     - consume topicName and republish to echoTopicName; the far end of a round trip.
     */
    @Value("${myapp.role:loopback}")
    private String role;
    @Value("${myapp.kafka.bootstrapAddress}")
    private String bootstrapAddress;//="192.168.0.60:9092";
    @Value("${myapp.messenger}")
    private String messenger;
    @Value("${myapp.kafka.groupId:default-consumer}")
    private String groupId;
    /**
     * Defaults to latest because this is primarily a latency probe: a consumer that starts behind
     * measures the age of history rather than current cluster performance. A backlogged topic was
     * observed reporting a 91-second sample, which swamped the mean and max while p50 sat at 16ms.
     * Switch to earliest when completeness matters more than latency - checking for lost messages,
     * for instance - and accept that the first pass will report the backlog's age.
     */
    @Value("${myapp.kafka.autoOffsetReset:latest}")
    private String autoOffsetReset;
    // Defaults to "all" so an environment that omits the key still exercises replication. With
    // acks=0 the producer does not wait for even a leader acknowledgement, which makes any
    // durability or delivery check meaningless.
    // How long a send may block before it throws. Kafka's own default is 60s, which on this
    // probe means a broker outage produces no signal at all for a full minute: the send sits
    // inside max.block.ms, nothing throws, so WorkerLoop records no failure and health still
    // reports UP. Ten seconds is long enough for an ordinary metadata refresh and short enough
    // that an outage surfaces while it is still happening - which is the whole point of the tool.
    @Value("${myapp.kafka.maxBlockMs:10000}")
    private int maxBlockMs;

    // The other half of the slow-failure problem. maxBlockMs bounds a send that cannot even be
    // buffered; this bounds one that WAS buffered and is never acknowledged - Kafka defaults that
    // to 120s, so a record accepted just before an outage takes two minutes to report.
    //
    // Kafka rejects delivery.timeout.ms < linger.ms + request.timeout.ms. Do NOT assume that floor
    // is 30000: Kafka 4 changed the linger.ms default from 0 to 5, so the real minimum is 30005,
    // and a value of exactly 30000 makes the producer throw ConfigException on construction. The
    // factory builds the producer lazily, so that surfaces as every send failing at runtime rather
    // than as a startup error - it cost a full test run to find. 40s leaves headroom for a
    // moderate linger or request timeout without needing this recalculated.
    // How long a run of empty polls may pass before the reader actively proves the cluster is
    // still reachable. 0 disables it. See the note in KafkaReader.verifyReachable on the overlap
    // with commitSync, which usually - but not always - detects an outage first.
    @Value("${myapp.kafka.reachabilityCheckSeconds:30}")
    private long reachabilityCheckSeconds;

    @Value("${myapp.kafka.deliveryTimeoutMs:40000}")
    private int deliveryTimeoutMs;

    @Value("${myapp.kafka.acks:all}")
    private String acksConfig;
    @Value("${myapp.publisher.sleep:10}")
    private int sleepConfig;
    @Value("${myapp.publisher.fillerSize:0}")
    private int fillerSize;
    // json | avro. The reading is identical either way - this decides only how it is encoded.
    // JSON stays the default because it needs no registry and is readable straight off the topic
    // with kafka-console-consumer, which matters for a probe that has to run anywhere.
    @Value("${myapp.payload.format:json}")
    private String payloadFormat;

    @Value("${myapp.schemaRegistry.url:}")
    private String schemaRegistryUrl;

    @Value("${myapp.schemaRegistry.cacheCapacity:100}")
    private int schemaRegistryCacheCapacity;

    @Value("${myapp.publisher.keyCount:0}")
    private int keyCount;

    @Value("${myapp.publisher.messageCount:1}")
    private int messageCount;
    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Fails fast on a role/topic combination that cannot work. The same-topic check matters most:
     * an echo relay pointed at its own inbound topic re-consumes everything it publishes, which is
     * an unbounded amplification loop against the cluster under test.
     */
    @PostConstruct
    void validateRole() {
        if (!isRole(LOOPBACK) && !isRole(ORIGIN) && !isRole(ECHO)) {
            throw new IllegalStateException("myapp.role must be one of " + LOOPBACK + ", "
                    + ORIGIN + ", " + ECHO + " but was '" + role + "'");
        }
        if (isRole(LOOPBACK)) {
            return;
        }
        if (echoTopicName == null || echoTopicName.isBlank()) {
            throw new IllegalStateException(
                    "myapp.kafka.echoTopicName is required for the '" + role + "' role");
        }
        if (echoTopicName.equals(topicName)) {
            throw new IllegalStateException("myapp.kafka.echoTopicName and myapp.kafka.topicName "
                    + "must differ for the '" + role + "' role, otherwise the relay re-consumes "
                    + "its own output and amplifies without bound (both are '" + topicName + "')");
        }
        logger.info("Role '{}': writing to '{}', reading from '{}'.", role, writerTopic(), readerTopic());
    }

    private boolean isRole(String candidate) {
        return candidate.equalsIgnoreCase(role);
    }

    /** Topic this instance publishes to; the echo role sends the return leg. */
    private String writerTopic() {
        return isRole(ECHO) ? echoTopicName : topicName;
    }

    /** Topic this instance consumes; the origin role listens on the return leg. */
    private String readerTopic() {
        return isRole(ORIGIN) ? echoTopicName : topicName;
    }

    /**
     * Shared by the writer and reader in queue mode, so it must stay a {@code @Bean}: the
     * CGLIB-proxied method returns the same instance to both, whereas a plain method would hand
     * each side its own queue.
     */
    @Bean
    public QueueManager queueManager() {
        return new QueueManager();
    }

    /** The one and only MessageWriter bean; the per-transport variants below are plain objects. */
    @Bean
    public MessageWriter messageWriter() {
        if (messenger.equalsIgnoreCase("console")) {
            logger.info("Creating console writer.");
            return new ConsoleWriter();
        }
        if (messenger.equalsIgnoreCase("kafka")) {
            logger.info("Creating kafka writer.");
            return kafkaWriter();
        }
        if (messenger.equalsIgnoreCase("queue")) {
            logger.info("Creating queue writer.");
            return queueManager();
        }
        logger.info("Invalid writer '{}' using empty writer.", messenger);
        return new EmptyWriter();
    }

    /**
     * The one and only MessageReader bean. destroyMethod is explicit rather than relying on
     * Spring's close()/shutdown() inference: this is the single place the reader gets closed, and
     * the Watcher deliberately no longer does it.
     */
    @Bean(destroyMethod = "close")
    public MessageReader messageReader() {
        if (messenger.equalsIgnoreCase("queue")) {
            logger.info("Creating queue message reader.");
            return queueManager();
        }
        if (messenger.equalsIgnoreCase("kafka")) {
            logger.info("Creating kafka message reader.");
            return new KafkaReader(consumerFactory(), readerTopic(), meterRegistry,
                    Duration.ofSeconds(reachabilityCheckSeconds));
        }
        logger.info("Creating empty message reader.");
        return new EmptyReader();
    }

    // The Kafka plumbing below is deliberately NOT exposed as beans. Declaring it as @Bean forced
    // every method to return null outside kafka mode, which registered NullBeans and made
    // by-type lookups ambiguous. Nothing outside this class injects these types.

    private KafkaWriter kafkaWriter() {
        return new KafkaWriter(new KafkaTemplate<>(producerFactory()), writerTopic(), meterRegistry);
    }

    private ProducerFactory<String, byte[]> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        logger.debug("Producer factory: bootstrap server: {}", bootstrapAddress);
        configProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapAddress);
        configProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        // Bytes, always. The application owns the payload encoding - JSON or Avro - so the client
        // must not second-guess it. Swapping in KafkaAvroSerializer here instead would move that
        // decision into the transport and make a mixed-format topic impossible to consume.
        configProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, acksConfig);
        // Bounded and single-attempt, so the retry decision stays with WorkerLoop - only the loop
        // can see the quit flag. See the blocking-call rule in CLAUDE.md.
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        ProducerFactory<String, byte[]> pf = new DefaultKafkaProducerFactory<String, byte[]>(configProps);
        pf.addListener(new MicrometerProducerListener<>(this.meterRegistry));
        return pf;
    }

    private ConsumerFactory<String, byte[]> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        logger.info("Consumer: bootstrap server: {}", bootstrapAddress);
        configProps.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapAddress);
        // Only consulted when this group has no committed offset. Replaces the seekToBeginning
        // KafkaReader used to do on every partition assignment, which discarded committed offsets
        // and replayed the topic on each rebalance.
        configProps.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                autoOffsetReset);
        configProps.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId);
        configProps.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        configProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        ConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<String, byte[]>(configProps);
        cf.addListener(new MicrometerConsumerListener<>(this.meterRegistry));
        return cf;
    }

    // Calling the factory methods directly rather than injecting by type: QueueManager implements
    // both MessageWriter and MessageReader, so by-type resolution was previously ambiguous.
    /** Shared by the generator that issues sequences and the sink that reconciles them. */
    @Bean
    public DeliveryLedger deliveryLedger() {
        return new DeliveryLedger(meterRegistry);
    }

    /*
     * destroyMethod stops the worker loop when the context closes. Without it nothing called
     * teardown() on the normal shutdown path: the loops were left running and the JVM tore their
     * (non-daemon) threads down on exit, so WorkerLoop's escalation - shutdown(), wait, then
     * shutdownNow() - never ran and a publisher mid-send was killed rather than drained.
     *
     * Spring destroys beans in reverse dependency order, so these engines shut down before the
     * messageReader they depend on is closed. That ordering matters: closing the reader first
     * makes an in-flight poll throw, which is how it happened to work before.
     */
    /**
     * The codec the publisher encodes with. Avro needs a registry; asking for it without one
     * configured fails here rather than on the first send, because a probe that starts and then
     * silently produces nothing is worse than one that refuses to start.
     */
    private TemperatureCodec writerCodec() {
        if (!"avro".equalsIgnoreCase(payloadFormat)) {
            if (!"json".equalsIgnoreCase(payloadFormat)) {
                logger.warn("Unknown myapp.payload.format '{}'; using json.", payloadFormat);
            }
            return new JsonTemperatureCodec();
        }
        if (schemaRegistryUrl.isBlank()) {
            throw new IllegalStateException(
                    "myapp.payload.format=avro needs myapp.schemaRegistry.url to be set");
        }
        return new AvroTemperatureCodec(schemaRegistryClient(), writerTopic(),
                Map.of("schema.registry.url", schemaRegistryUrl));
    }

    /**
     * Codecs the consumer will try, in order. Both are offered whenever a registry is configured,
     * regardless of what this instance publishes: the topic can hold either format at once during
     * a migration, and a consumer that only understood its own output would report the rest as
     * loss the cluster never caused.
     */
    private List<TemperatureCodec> readerCodecs() {
        if (schemaRegistryUrl.isBlank()) {
            return List.of(new JsonTemperatureCodec());
        }
        return List.of(
                new JsonTemperatureCodec(),
                new AvroTemperatureCodec(schemaRegistryClient(), readerTopic(),
                        Map.of("schema.registry.url", schemaRegistryUrl)));
    }

    private SchemaRegistryClient schemaRegistryClient() {
        return new CachedSchemaRegistryClient(schemaRegistryUrl, schemaRegistryCacheCapacity);
    }

    @Bean(destroyMethod = "teardown")
    public Publisher publisher() {
        var producer = new TemperatureGenerator(deliveryLedger(), keyCount, writerCodec());
        var publisher = new Publisher(producer, messageWriter(), deliveryLedger());
        publisher.setSleep(sleepConfig);
        publisher.setFillerSize(fillerSize);
        publisher.setMessages(messageCount);
        return publisher;
    }

    @Bean(destroyMethod = "teardown")
    public Watcher<Temperature> watcher() {
        var consumer = new TemperatureConsumer(readerCodecs());
        return new Watcher<>(consumer, messageReader());
    }

    /**
     * Only the echo role runs a relay, and it starts itself: it is a pure pump with nothing to
     * configure at runtime, so a deployed echo instance should just work.
     */
    @Bean(destroyMethod = "teardown")
    @ConditionalOnProperty(name = "myapp.role", havingValue = ECHO)
    public Relay relay() {
        MessageReader reader = messageReader();
        MessageWriter writer = messageWriter();
        // The topic check in validateRole() cannot catch this: with messenger=queue both sides are
        // the same in-memory QueueManager, so the relay would read its own output straight back
        // and loop without bound. Distinct endpoints are what make an echo leg meaningful.
        if (reader == writer) {
            throw new IllegalStateException("the '" + ECHO + "' role needs a transport with "
                    + "separate inbound and outbound channels, but messenger '" + messenger
                    + "' reads and writes the same one");
        }
        Relay relay = new Relay(reader, writer, meterRegistry);
        relay.initiate();
        return relay;
    }

    /*
     * Readiness contributors, one per engine. Spring derives the contributor id from the bean name
     * by stripping the "HealthIndicator" suffix, giving "publisher", "consumer" and "relay" - the
     * keys the readiness group in application.yaml lists. The names deliberately differ from the
     * engine beans themselves (publisher, watcher, relay); reusing "relay" for the indicator once
     * collided with the Relay bean and stopped the echo role starting at all.
     *
     * The publisher and watcher contributors are registered in every role. In the echo role those
     * engines are never started, so they report running:false and UP, which costs nothing and
     * avoids a second conditional that could drift out of step with the engines'.
     */
    /**
     * Binds outage timing for every engine. Registered as an InitializingBean rather than inside
     * each engine's factory method so the instrumentation lives in one place and the pipeline
     * classes stay free of Micrometer - the same split PipelineHealthIndicator uses.
     */
    @Bean
    public InitializingBean recoveryMetricsBinder(Publisher publisher, Watcher<Temperature> watcher,
                                                  ObjectProvider<Relay> relay) {
        return () -> {
            RecoveryMetrics.bind(meterRegistry, "publisher", publisher);
            RecoveryMetrics.bind(meterRegistry, "consumer", watcher);
            Relay actual = relay.getIfAvailable();
            if (actual != null) {
                RecoveryMetrics.bind(meterRegistry, "relay", actual);
            }
        };
    }

    @Bean
    public PipelineHealthIndicator publisherHealthIndicator(Publisher publisher) {
        return new PipelineHealthIndicator(publisher);
    }

    @Bean
    public PipelineHealthIndicator consumerHealthIndicator(Watcher<Temperature> watcher) {
        return new PipelineHealthIndicator(watcher);
    }

    /*
     * Registered in every role, not just echo. A conditional contributor would force
     * validate-group-membership off, because the readiness group naming it would fail startup in
     * every role that lacks it - and that switch being off is what lets a renamed contributor drop
     * out of the group silently.
     */
    @Bean
    public PipelineHealthIndicator relayHealthIndicator(ObjectProvider<Relay> relay) {
        Relay actual = relay.getIfAvailable();
        return new PipelineHealthIndicator(actual != null ? actual : EngineStatus.idle());
    }

    /**
     * Only present when myapp.verify.messages is set, which is what turns an interactive probe
     * into a build step. Absent that property the application behaves exactly as before, so this
     * cannot surprise a long-running deployment by exiting under it.
     */
    @Bean
    @ConditionalOnProperty(name = "myapp.verify.messages")
    public VerificationRunner verificationRunner(
            Publisher publisher, Watcher<Temperature> watcher, DeliveryLedger ledger,
            ConfigurableApplicationContext context,
            @Value("${myapp.verify.messages}") long targetMessages,
            @Value("${myapp.verify.timeoutSeconds:120}") long timeoutSeconds,
            @Value("${myapp.verify.drainSeconds:30}") long drainSeconds,
            @Value("${myapp.verify.attachSeconds:30}") long attachSeconds) {
        return new VerificationRunner(publisher, watcher, ledger, context, targetMessages,
                Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(drainSeconds),
                Duration.ofSeconds(attachSeconds));
    }

    @Bean
    public ManagementAgent managementAgent(Publisher publisher) {
        var agent = new ManagementAgentImpl();
        try {
            MBeanContainer mbc = new MBeanContainer(publisher, PublisherManager.class);
            mbc.setName("name=publisherManager");
            agent.addBean(mbc);
        } catch (NotCompliantMBeanException ignored) {
        }
        return agent;
    }
}
