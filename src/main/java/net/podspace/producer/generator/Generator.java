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
    private boolean started;
    private boolean quit;
    private final MessageProducer producer;
    private String topicName;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ExecutorService pool;

    public Generator(MessageProducer producer) {
        this.producer = producer;
        this.started = false;
    }

    public void resume() {
        pause = false;
    }
    public void pause() {
        pause = true;
    }
    public void quit() {
        logger.info("In quit method...");
        quit = true;
        teardown();
        logger.info("torn down...");
    }
    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }
    public void setSeconds(long seconds) {
        this.seconds = seconds;
    }
    public long getSeconds() {
        return this.seconds;
    }
    public void setKafkaTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void initiate() {
        if (started) {
            logger.info("already started... leaving");
            return;
        }

        started = true;
        quit = false;
        pause = false;
        logger.info("starting thread pool");
        pool = Executors.newFixedThreadPool(1);
        pool.submit(this);
    }

    public void teardown() {
        try {
            if (! started) {
                logger.info("teardown: not started, leaving");
            }
            if (pool == null) {
                logger.info("teardown: pool null");
                return;
            }
            logger.info("teardown: shutting down");
            pool.shutdown();
            pool.close();
            while (!pool.awaitTermination(5L, TimeUnit.MINUTES)) {
                logger.info("Not yet. Still waiting for termination");
            }

            quit = false;
            started = false;
            pause = false;
        } catch(InterruptedException ignored) {}
        logger.info("teardown: generator thread shut down.");
    }

    @Override
    public void run() {
        logger.info("In run method, starting stream...");
        sendMessageStream();
        logger.info("In run method, streaming done...");
    }

    public void sendMessageStream() {
        logger.info("In stream method...");
        while (!quit) {
            if (pause) {
                logger.info("streaming paused...");
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
