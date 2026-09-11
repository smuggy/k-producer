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
    // TODO: dead code - setSize() is never called, and because the queue is built in the
    // constructor from this static, calling it after the bean exists would have no effect anyway.
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
        // TODO: bug - mirror of readMessage(): blocks forever once the 10-slot queue fills with no
        // consumer draining it, so /publisher/stop hangs for the same reason.
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
        // TODO: bug - this spins forever while the queue stays empty; it only exits on a message
        // or an interrupt, and never checks the caller's quit flag. Watcher.teardown() calls
        // pool.shutdown()/close(), neither of which interrupts a running task, so with
        // myapp.messenger=queue and no producer running, GET /consumer/stop blocks its request
        // thread indefinitely. Return an empty list after the poll times out instead.
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
}
