package net.podspace.producer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
public class HelloController {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private final MyBean b;
    private final String topicName = "test-topic-one";
    private final KafkaTemplate<String, String> kafkaTemplate;

    public HelloController(MyBean b, KafkaTemplate<String,String> kafkaTemplate) {
        this.b = b;
        this.kafkaTemplate = kafkaTemplate;
    }

    @GetMapping("/")
    public String index() {
        return "Greetings";
    }

    @GetMapping("/other")
    public String other() {
        sendMessage("I got called");
        return "Value = " + b.getValue();
    }

    public void sendMessage(String message) {
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topicName, message);
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                logger.info("Sent message=[" + message +
                        "] with offset=[" + result.getRecordMetadata().offset() + "]");
            } else {
                logger.info("Unable to send message=[" +
                        message + "] due to : " + ex.getMessage());
            }
        });
    }
}
