package net.podspace.producer.generator;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class KafkaReader implements MessageReader, ConsumerRebalanceListener {
    private static final Logger logger = LoggerFactory.getLogger(KafkaReader.class);
    private final Consumer<String,String> consumer;

    public KafkaReader(ConsumerFactory<String,String> consumer, String topicName) {
        this.consumer = consumer.createConsumer();
        this.consumer.subscribe(Collections.singletonList(topicName), this);
    }

    public List<String> readMessage() {
        List<String> ret = new ArrayList<>();
        logger.debug("Listening for message.");

        ConsumerRecords<String,String> records = consumer.poll(Duration.ofSeconds(10));
        for (ConsumerRecord<String, String> r : records) {
            ret.add(r.value());
        }
        consumer.commitSync(Duration.ofSeconds(1));
        return ret;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        try {
            consumer.commitSync(Duration.ofSeconds(5));
        } catch (KafkaException e) {
            logger.warn("Failed to commit offsets during rebalance for partitions {}", partitions, e);
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // TODO: bug - unconditionally seeks to beginning on every rebalance (not just first
        // assignment), discarding committed offsets and replaying the whole topic on restarts/rebalances.
        consumer.seekToBeginning(partitions);
    }
    @Override
    public void close() {
        consumer.close();
    }
}
