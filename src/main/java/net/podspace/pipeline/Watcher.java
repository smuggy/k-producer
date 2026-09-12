package net.podspace.pipeline;

import net.podspace.messaging.MessageConsumer;
import net.podspace.messaging.MessageReader;
import net.podspace.messaging.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

public class Watcher<T extends Comparable<T>> {
    private static final Logger logger = LoggerFactory.getLogger(Watcher.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final long PAUSE_MILLIS = 5_000;
    private final MessageConsumer<T> consumer;
    private final MessageReader reader;
    private final WorkerLoop loop;
    private BlockingQueue<ValueEnvelope<T>> items;

    public Watcher(MessageConsumer<T> c, MessageReader r) {
        this.reader = r;
        this.consumer = c;
        // TODO: bug - closing on exit permanently closes the singleton reader's underlying
        // consumer; a later /consumer/start resubmits the same bean and reader.readMessage() then
        // throws on the closed consumer, silently no-op'ing further consumption until app restart.
        this.loop = new WorkerLoop("watcher", PAUSE_MILLIS, this::pollAndRecord, reader::close);
    }

    public void setReturnQueue(BlockingQueue<ValueEnvelope<T>> queue) {
        this.items = queue;
    }

    public void initiate() {
        loop.initiate();
    }

    public void teardown() {
        loop.teardown();
    }

    public void quit() {
        loop.teardown();
    }

    public void pause() {
        loop.pause();
    }

    public void resume() {
        loop.resume();
    }

    /** One pass: read whatever is available and record a latency sample for each parsed message. */
    private void pollAndRecord() {
        List<String> list = reader.readMessage();
        if (list.isEmpty()) {
            logger.info("No message available... wait again.");
            return;
        }
        for (String mess : list) {
            Optional<Pair<T, Integer>> val = consumer.getMessage(mess);
            if (val.isEmpty()) {
                logger.info("No value present or parsable in message: {}", mess);
                continue;
            }
            logger.debug("Value is: {}", val.get());
            if (items == null) {
                logger.info("No queue provided, dropping item.");
                continue;
            }
            ValueEnvelope<T> envelope = new ValueEnvelope<>();
            envelope.item = val.get().a;
            envelope.size = val.get().b;
            envelope.time = LocalDateTime.now().format(formatter);
            if (items.offer(envelope)) {
                logger.debug("added item to blocking queue.");
            } else {
                logger.debug("unable to add item to blocking queue.");
            }
        }
    }
}
