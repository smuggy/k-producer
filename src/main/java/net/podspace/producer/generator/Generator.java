package net.podspace.producer.generator;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Generator implements Runnable, GeneratorManager {
    private static final Logger logger = LoggerFactory.getLogger(Generator.class);
    private long seconds;
    private boolean pause;
    private boolean quit;
    private final MessageProducer producer;
    private String topicName;
    private KafkaTemplate<String, String> kafkaTemplate;

    public Generator(MessageProducer producer) {
        this.producer = producer;
    }

    public void resume() {
        pause = false;
    }
    public void pause() {
        pause = true;
    }
    public void quit() {
        quit = true;
    }
    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }
    public void setSeconds(long seconds) {
        this.seconds = seconds;
    }
    public void setKafkaTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void initiate() {
        try {
            ExecutorService pool = Executors.newFixedThreadPool(1);
            pool.submit(this);
            pool.shutdown();
            pool.close();
            while (!pool.awaitTermination(5L, TimeUnit.MINUTES)) {
                logger.info("Not yet. Still waiting for termination");
            }
        } catch(InterruptedException ignored) {}
    }

    @Override
    public void run() {
        sendMessageStream();
    }

    public void sendMessageStream() {
        while (!quit) {
            if (pause) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {}
                continue;
            }

            String message = producer.createMessage();
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

            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException ignored) {}
        }
    }

}
