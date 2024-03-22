package net.podspace.producer.generator;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KafkaReader implements MessageReader{
    private static final Logger logger = LoggerFactory.getLogger(KafkaReader.class);
    private final Consumer<String,String> consumer;

    public KafkaReader(ConsumerFactory<String,String> consumer, String topicName) {
        this.consumer = consumer.createConsumer();
        this.consumer.subscribe(Collections.singletonList(topicName));
    }

    public List<String> readMessage() {
        List<String> ret = new ArrayList<>();
        logger.info("Listening for message.");

        ConsumerRecords<String,String> records = consumer.poll(Duration.ofSeconds(10));
        for (ConsumerRecord<String, String> r : records) {
            ret.add(r.value());
        }
        consumer.commitSync(Duration.ofSeconds(1));
        return ret;
    }
}
