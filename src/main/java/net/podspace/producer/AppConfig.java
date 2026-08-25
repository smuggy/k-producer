package net.podspace.producer;

import io.micrometer.core.instrument.MeterRegistry;
import net.podspace.consumer.Watcher;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
import net.podspace.domain.TemperatureGenerator;
import net.podspace.management.MBeanContainer;
import net.podspace.management.ManagementAgent;
import net.podspace.management.ManagementAgentImpl;
import net.podspace.producer.generator.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.core.*;

import javax.management.NotCompliantMBeanException;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class.getName());

    @Value("${myapp.val}")
    private String val;
    @Value("${myapp.kafka.topicName}")
    private String topicName;//="test-topic-one";
    @Value("${myapp.kafka.bootstrapAddress}")
    private String bootstrapAddress;//="192.168.1.60:9092";
    @Value("${myapp.messenger}")
    private String messenger;
    @Value("${myapp.kafka.groupId}")
    private String groupId;
    @Value("${myapp.kafka.acks}")
    private String acksConfig;
    @Value("${myapp.publisher.sleep:10}")
    private int sleepConfig;
    @Value("${myapp.publisher.fillerSize:0}")
    private int fillerSize;
    @Value("${myapp.publisher.messageCount:1}")
    private int messageCount;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private MeterRegistry meterRegistry;

    @Bean
    public MyBean beanInstance() {
        logger.info("==> Creating bean instance with {}", val);

        MyBean m = new MyBean();
        m.setValue(val);
        return m;
    }

//    @Bean
//    public KafkaAdmin kafkaAdmin() {
//        Map<String, Object> configs = new HashMap<>();
//        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
//        return new KafkaAdmin(configs);
//    }

    @Bean
    public KafkaWriter kafkaWriter() {
        if (!messenger.equalsIgnoreCase("kafka"))
            return null;
        var writer = new KafkaWriter();
        writer.setTopicName(topicName);
        writer.setKafkaTemplate(kafkaTemplate());
        return writer;
    }

    @Bean
    public KafkaReader kafkaReader() {
        if (!messenger.equalsIgnoreCase("kafka"))
            return null;
        return new KafkaReader(consumerFactory(), topicName);
    }

    @Bean
    public ConsoleWriter consoleWriter() {
        return new ConsoleWriter();
    }

    @Bean
    public EmptyWriter emptyWriter() {
        return new EmptyWriter();
    }

    @Bean
    public EmptyReader emptyReader() {
        return new EmptyReader();
    }

    @Bean
    @Scope("singleton")
    public QueueManager queueManager() {
        return new QueueManager();
    }

    @Bean
    public MessageWriter messageWriter() {
        if (messenger.equalsIgnoreCase("console")) {
            logger.info("Creating console writer.");
            return consoleWriter();
        }
        if (messenger.equalsIgnoreCase("kafka")) {
            logger.info("Creating kafka writer.");
            return kafkaWriter();
        }
        if (messenger.equalsIgnoreCase("queue")) {
            logger.info("Creating queue writer.");
            return context.getBean(QueueManager.class);
        }
        logger.info("Invalid writer '{}' using empty writer.", messenger);
        return emptyWriter();
    }

    @Bean
    public MessageReader messageReader() {
        if (messenger.equalsIgnoreCase("queue")) {
            logger.info("Creating queue message reader.");
            return context.getBean(QueueManager.class);
        }
        if (messenger.equalsIgnoreCase("kafka")) {
            logger.info("Creating kafka message reader.");
            return context.getBean(KafkaReader.class);
        }
        logger.info("Creating empty message reader.");
        return emptyReader();
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        if (!messenger.equalsIgnoreCase("kafka"))
            return null;
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

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        if (!messenger.equalsIgnoreCase("kafka"))
            return null;
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        if (!messenger.equalsIgnoreCase("kafka"))
            return null;
        Map<String, Object> configProps = new HashMap<>();
        logger.info("Consumer: bootstrap server: {}", bootstrapAddress);
        configProps.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapAddress);
//        configProps.put(
//                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
//                "earliest");
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

    @Bean
    @Scope("singleton")
    public Publisher publisher(MessageWriter messageWriter) {
        var producer = new TemperatureGenerator();
        var publisher = new Publisher(producer, messageWriter);
        publisher.setSleep(sleepConfig);
        publisher.setFillerSize(fillerSize);
        publisher.setMessages(messageCount);
        return publisher;
    }

    @Bean
    @Scope("singleton")
    public Watcher<Temperature> watcher(MessageReader messageReader) {
        var consumer = new TemperatureConsumer();
        return new Watcher<>(consumer, messageReader);
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
