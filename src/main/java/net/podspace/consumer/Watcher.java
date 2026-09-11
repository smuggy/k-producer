package net.podspace.consumer;

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

public class Watcher<T extends Comparable<T>> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 10;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final MessageConsumer<T> consumer;
    private final MessageReader reader;
    // Written by request threads, read by the watcher worker thread; volatile supplies the
    // happens-before edge so stop/pause are observed.
    private volatile boolean quit;
    private volatile boolean pause;
    // Guarded by the synchronized lifecycle methods below.
    private boolean started;
    private ExecutorService pool;
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

    public synchronized void initiate() {
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

    public synchronized void teardown() {
        try {
            quit = true;
            if (!started) {
                logger.info("teardown: not started, leaving");
                return;
            }
            if (pool == null) {
                logger.info("teardown: pool null");
                return;
            }
            logger.info("teardown: shutting down");
            // Escalate rather than waiting forever: shutdown() never interrupts, and close()
            // blocks indefinitely, so a worker parked in a blocking read used to wedge this
            // request thread permanently.
            pool.shutdown();
            if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.info("teardown: worker still running, interrupting it.");
                pool.shutdownNow();
                if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("teardown: worker did not respond to interrupt.");
                }
            }

            quit = false;
            started = false;
            pause = false;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        logger.info("teardown: generator thread shut down.");
    }

    @Override
    public void run() {
        logger.info("In run method, starting retrieval...");
        retrieveMessageStream();
        logger.info("In run method, retrieval done...");
    }

    private void retrieveMessageStream() {
        logger.debug("In retrieve stream method...");
        try {
            while (!quit) {
                if (pause) {
                    logger.info("retrieving paused...");
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException i) {
                        logger.warn("Exception occurred while paused: ", i);
                    }
                    continue;
                }

                var list = reader.readMessage();
                if (list.isEmpty()) {
                    logger.info("No message available... wait again.");
                    continue;
                }
                for (String mess : list) {
                    Optional<Pair<T, Integer>> val = consumer.getMessage(mess);
                    if (val.isPresent()) {
                        logger.debug("Value is: {}", val.get());
                        if (items != null) {
                            ValueEnvelope<T> envelope = new ValueEnvelope<>();
                            envelope.item = val.get().a;
                            envelope.size = val.get().b;
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
                        logger.info("No value present or parsable in message: {}", mess);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Exception occurred while retrieving message: ", e);
        } finally {
            // TODO: bug - permanently closes the singleton reader's underlying consumer; a later
            // /consumer/start resubmits the same bean and reader.readMessage() then throws on the
            // closed consumer, silently no-op'ing further consumption until app restart.
            reader.close();
        }
    }
}
