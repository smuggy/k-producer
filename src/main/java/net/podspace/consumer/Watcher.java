package net.podspace.consumer;

import io.micrometer.core.annotation.Counted;
import io.prometheus.client.Summary;
import net.podspace.producer.generator.MessageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Watcher<T> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private boolean started;
    private boolean quit;
    private boolean pause;
    private ExecutorService pool;
    private final MessageConsumer<T> consumer;
    private final MessageReader reader;
    private BlockingQueue<ValueEnvelope<T>> items;

    public Watcher(MessageConsumer<T> c, MessageReader r) {
        this.started = false;
        this.pause = false;
        this.reader = r;
        this.consumer = c;
    }

    public void setReturnQueue(BlockingQueue<ValueEnvelope<T>> queue) {
        this.items = queue;
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
            quit = true;
            if (!started) {
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
        } catch (InterruptedException ignored) {
        }
        logger.info("teardown: generator thread shut down.");
    }

    @Override
    public void run() {
        logger.info("In run method, starting retrieval...");
        retrieveMessageStream();
        logger.info("In run method, retrieval done...");
    }

    @Counted
    private void retrieveMessageStream() {
        logger.debug("In retrieve stream method...");
        while (!quit) {
            if (pause) {
                logger.info("retrieving paused...");
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            var list = reader.readMessage();
            if (list.isEmpty()) {
                logger.info("No message available... wait again.");
            } else {
                for (String mess : list) {
                    Optional<T> val = consumer.getMessage(mess);
                    if (val.isPresent()) {
                        logger.debug("Value is: " + val.get());
                        if (items != null) {
                            ValueEnvelope<T> envelope = new ValueEnvelope<>();
                            envelope.item = val.get();
                            envelope.time = LocalDateTime.now().format(formatter);
                            if (items.offer(envelope)) {
                                logger.debug("added item to blocking queue.");
                            } else {
                                logger.debug("unable to add item to blocking queue.");
                            }
                        } else {
                            logger.info("No queue provided, dropping item.");
                        }
                    } else {
                        logger.info("No value present or parsable in message: " + mess);
                    }
                }
            }
        }
    }
}
