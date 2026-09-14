package net.podspace.config;

import io.micrometer.core.instrument.MeterRegistry;
import net.podspace.pipeline.Watcher;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
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
import net.podspace.pipeline.Relay;
import net.podspace.pipeline.PublisherManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import jakarta.annotation.PostConstruct;
import javax.management.NotCompliantMBeanException;
import java.util.HashMap;
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
    private String bootstrapAddress;//="192.168.1.60:9092";
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
    @Value("${myapp.kafka.acks:all}")
    private String acksConfig;
    @Value("${myapp.publisher.sleep:10}")
    private int sleepConfig;
    @Value("${myapp.publisher.fillerSize:0}")
    private int fillerSize;
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
            return new KafkaReader(consumerFactory(), readerTopic(), meterRegistry);
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

    private ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        logger.debug("Producer factory: bootstrap server: {}", bootstrapAddress);
        configProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapAddress);
        configProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        configProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, acksConfig);
        ProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(configProps);
        pf.addListener(new MicrometerProducerListener<>(this.meterRegistry));
        return pf;
    }

    private ConsumerFactory<String, String> consumerFactory() {
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
                StringDeserializer.class.getName());
        ConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(configProps);
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

    @Bean
    public Publisher publisher() {
        var producer = new TemperatureGenerator(deliveryLedger());
        var publisher = new Publisher(producer, messageWriter());
        publisher.setSleep(sleepConfig);
        publisher.setFillerSize(fillerSize);
        publisher.setMessages(messageCount);
        return publisher;
    }

    @Bean
    public Watcher<Temperature> watcher() {
        var consumer = new TemperatureConsumer();
        return new Watcher<>(consumer, messageReader());
    }

    /**
     * Only the echo role runs a relay, and it starts itself: it is a pure pump with nothing to
     * configure at runtime, so a deployed echo instance should just work.
     */
    @Bean
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
