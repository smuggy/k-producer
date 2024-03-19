package net.podspace.producer.generator;

import io.micrometer.core.annotation.Counted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Publisher implements Runnable, PublisherManager {
    private static final Logger logger = LoggerFactory.getLogger(Publisher.class);
    private long seconds;
    private boolean pause;
    private boolean started;
    private boolean quit;
    private long messages;
    private final MessageGenerator generator;
    private final MessageWriter writer;
    private ExecutorService pool;

    public Publisher(MessageGenerator generator, MessageWriter writer) {
        this.generator = generator;
        this.writer = writer;
        this.messages = 1;
        this.seconds = 5;
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
    public void setSeconds(long seconds) {
        if (this.seconds < 0) this.seconds = 5;
        else this.seconds = seconds;
    }
    public long getSeconds() {
        return this.seconds;
    }
    public void setFillerSize(int size) {
        generator.setFillerSize(size);
    }
    public int getFillerSize() {
        return generator.getFillerSize();
    }

    public void setMessages(long count) {
        if (count < 1) this.messages = 1;
        else this.messages = count;
    }
    public long getMessages() {
        return messages;
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

    @Counted
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

            for (long i=0; i<this.messages; i++) {
                String message = generator.createMessage();
                writer.writeMessage(message);
            }
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException ignored) {}
        }
    }
}
