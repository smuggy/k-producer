package net.podspace.producer.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

public class KafkaWriter implements MessageWriter {
    private static final Logger logger = LoggerFactory.getLogger(KafkaWriter.class);
    private String topicName;
    private KafkaTemplate<String, String> kafkaTemplate;

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public void setKafkaTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void writeMessage(String message) {
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topicName, message);
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.debug("Sent message=[" + message +
                        "] with offset=[" + result.getRecordMetadata().offset() + "]");
            } else {
                logger.info("Unable to send message=[" +
                        message + "] due to : " + ex.getMessage());
            }
        });
    }
}
