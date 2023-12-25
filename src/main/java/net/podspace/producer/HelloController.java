package net.podspace.producer;

import net.podspace.producer.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
public class HelloController {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private final MyBean b;
    private static final String topicName = "test-topic-one";
    private final KafkaTemplate<String, User> kafkaTemplate;

    public HelloController(MyBean b, KafkaTemplate<String,User> kafkaTemplate) {
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
        User user = new User(1, "abc",123, "blue");
        CompletableFuture<SendResult<String, User>> future = kafkaTemplate.send(topicName, user);
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

    public void receive() {
        kafkaTemplate.receive(topicName, 0, 0);
    }

//    @KafkaListener(topics = "${kafka.topic.avro}")
//    public void receive(User user) {
//        LOGGER.info("received user='{}'", user.toString());
//        latch.countDown();
//    }
}
