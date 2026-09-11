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
    private static final int MAX_QUEUE_SIZE = 500;
    private final BlockingQueue<String> queue;

    public QueueManager() {
        queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    }

    /**
     * Makes a single bounded attempt to enqueue. Retrying is left to the caller's loop, which is
     * the only place that can see the quit flag; looping here made /publisher/stop hang once the
     * queue filled with no consumer draining it. A full queue drops the message, matching the
     * fire-and-forget behaviour of the other writers.
     */
    public void writeMessage(String message) {
        logger.debug("Writing message: {}", message);
        try {
            if (!queue.offer(message, SLEEP_TIME, TimeUnit.SECONDS)) {
                logger.warn("Queue still full after {}s, dropping message.", SLEEP_TIME);
            }
        } catch (InterruptedException ie) {
            logger.info("Write interrupted, dropping message.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Makes a single bounded poll and returns whatever it got, empty included. The caller's loop
     * owns the retrying and is the only place that can see the quit flag; looping here made
     * /consumer/stop hang whenever the queue stayed empty. The poll stays timed so that an empty
     * queue paces the caller instead of spinning it.
     */
    public List<String> readMessage() {
        List<String> ret = new ArrayList<>();
        try {
            String message = queue.poll(SLEEP_TIME, TimeUnit.SECONDS);
            if (message != null) {
                ret.add(message);
                queue.drainTo(ret);
            } else {
                logger.debug("No message available within {}s.", SLEEP_TIME);
            }
        } catch (InterruptedException ie) {
            logger.info("Read interrupted, returning what we have.");
            Thread.currentThread().interrupt();
        }
        return ret;
    }
}
