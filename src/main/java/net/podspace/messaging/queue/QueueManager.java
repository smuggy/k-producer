package net.podspace.messaging.queue;

import net.podspace.messaging.MessageReader;
import net.podspace.messaging.MessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class QueueManager implements MessageWriter, MessageReader {
    private static final Logger logger = LoggerFactory.getLogger(QueueManager.class);
    /**
     * Bounds both the read poll and the write offer. Kept short so a stop is observed promptly:
     * teardown can only interrupt a parked worker after its own escalation delay, so a long
     * timeout here shows up directly as /consumer/stop and /publisher/stop latency.
     */
    private static final long QUEUE_TIMEOUT_SECONDS = 5;
    private static final int MAX_QUEUE_SIZE = 500;
    /** Caps one read batch independently of queue capacity, so the two can be tuned separately. */
    private static final int MAX_BATCH_SIZE = 100;
    private final BlockingQueue<byte[]> queue;

    public QueueManager() {
        queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    }

    /**
     * Makes a single bounded attempt to enqueue. Retrying is left to the caller's loop, which is
     * the only place that can see the quit flag; looping here made /publisher/stop hang once the
     * queue filled with no consumer draining it. A full queue drops the message, matching the
     * fire-and-forget behaviour of the other writers.
     */
    public void writeMessage(byte[] message) {
        logger.debug("Writing message of {} bytes", message.length);
        try {
            if (!queue.offer(message, QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Queue still full after {}s, dropping message.", QUEUE_TIMEOUT_SECONDS);
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
    public List<byte[]> readMessage() {
        List<byte[]> ret = new ArrayList<>();
        try {
            // poll blocks for at least one message (and paces the caller when the queue is empty);
            // drainTo then sweeps up whatever else is already waiting without blocking again.
            byte[] message = queue.poll(QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (message != null) {
                ret.add(message);
                queue.drainTo(ret, MAX_BATCH_SIZE - ret.size());
            } else {
                logger.debug("No message available within {}s.", QUEUE_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException ie) {
            logger.info("Read interrupted, returning what we have.");
            Thread.currentThread().interrupt();
        }
        return ret;
    }
}
