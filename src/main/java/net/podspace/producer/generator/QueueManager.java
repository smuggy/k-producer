package net.podspace.producer.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class QueueManager implements MessageWriter, MessageReader {
    private static final Logger logger = LoggerFactory.getLogger(QueueManager.class);
    private static final long SLEEP_TIME = 30;
    private static Integer size = 10;
    private final BlockingQueue<String> queue;

    public QueueManager() {
        queue = new ArrayBlockingQueue<>(size);
    }

    public static void setSize(int size) {
        if (size > 0)
            QueueManager.size = size;
    }

    public void writeMessage(String message) {
        logger.info("Writing message: {}", message);
        while (true) {
            try {
                if (queue.offer(message, SLEEP_TIME, TimeUnit.SECONDS)) {
                    logger.info("Offer succeeded.");
                    break;
                } else {
                    logger.debug("Failed to offer, try again.");
                }
            } catch (InterruptedException ie) {
                logger.info("Write loop interrupted.", ie);
                break;
            }
        }
    }

    public List<String> readMessage() {
        List<String> ret = new ArrayList<>();
        logger.info("read message");
        while (true) {
            try {
                String message = queue.poll(SLEEP_TIME, TimeUnit.SECONDS);
                if (message != null) {
                    logger.info("read message, returning...");
                    ret.add(message);
                    break;
                } else {
                    logger.info("Null value on queue");
                    logger.info("queue size is: {}", queue.size());
                }
            } catch (InterruptedException ie) {
                logger.info("Read loop interrupted", ie);
                break;
            }
        }
        return ret;
    }

    @Override
    public void close() {
    }
}
