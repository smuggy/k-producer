package net.podspace.producer;

import net.podspace.management.MBeanContainer;
import net.podspace.management.ManagementAgent;
import net.podspace.management.ManagementAgentImpl;
import net.podspace.producer.generator.Generator;
import net.podspace.producer.generator.GeneratorManager;
import net.podspace.producer.generator.TemperatureProducer;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import javax.management.NotCompliantMBeanException;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;

@Configuration
public class AppConfig {
    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());
//    private static final String BOOTSTRAP_ADDRESS = "192.168.1.60:9092";

    @Value("${myapp.val}")
    private String val;
    @Value("${myapp.kafka.topicName}")
    private String topicName;//="test-topic-one";
    @Value("${myapp.kafka.bootstrapAddress}")
    private String bootstrapAddress;//="192.168.1.60:9092";

    private Generator generator;

    @Bean
    public MyBean beanInstance() {
        logger.info("==> Creating bean instance with " + val);// + "\t other value is");

        MyBean m = new MyBean();
        m.setValue(val);
        return m;
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
        return new KafkaAdmin(configs);
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapAddress);
        configProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        configProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    @Scope("prototype")
    public Generator generator() {
        if (generator == null) {
            var producer = new TemperatureProducer();
            generator = new Generator(producer);
            generator.setSeconds(5);
            generator.setTopicName(topicName);
            generator.setKafkaTemplate(kafkaTemplate());
        }
        return generator;
    }

    @Bean
    public ManagementAgent managementAgent() {
        var agent = new ManagementAgentImpl();
        try {
            MBeanContainer mbc = new MBeanContainer(generator(), GeneratorManager.class);
            mbc.setName("name=generatorManager");
            agent.addBean(mbc);
        } catch (NotCompliantMBeanException ignored) {}
        return agent;
    }
}
