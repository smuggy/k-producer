package net.podspace.pipeline;

import net.podspace.messaging.MessageGenerator;
import net.podspace.messaging.MessageWriter;

public class Publisher implements PublisherManager {
    private static final long PAUSE_MILLIS = 10_000;
    private final MessageGenerator generator;
    private final MessageWriter writer;
    private final WorkerLoop loop;
    // Written by request threads (PublisherController / JMX), read by the publisher worker thread.
    // volatile supplies the happens-before edge, and makes the 64-bit reads and writes atomic.
    private volatile long halfSeconds;
    private volatile long messages;

    public Publisher(MessageGenerator generator, MessageWriter writer) {
        this.generator = generator;
        this.writer = writer;
        this.messages = 1;
        this.halfSeconds = 10;
        this.loop = new WorkerLoop("publisher", PAUSE_MILLIS, this::publishBatch);
    }

    public void initiate() {
        loop.initiate();
    }

    public void teardown() {
        loop.teardown();
    }

    @Override
    public void quit() {
        loop.teardown();
    }

    @Override
    public void pause() {
        loop.pause();
    }

    @Override
    public void resume() {
        loop.resume();
    }

    @Override
    public long getSleep() {
        return this.halfSeconds;
    }

    @Override
    public void setSleep(long halfSeconds) {
        if (halfSeconds < 0) this.halfSeconds = 5;
        else if (halfSeconds == 0) this.halfSeconds = 1;
        else this.halfSeconds = halfSeconds;
    }

    @Override
    public int getFillerSize() {
        return generator.getFillerSize();
    }

    @Override
    public void setFillerSize(int size) {
        generator.setFillerSize(size);
    }

    @Override
    public long getMessages() {
        return messages;
    }

    @Override
    public void setMessages(long count) {
        if (count < 1) this.messages = 1;
        else this.messages = count;
    }

    /** One pass: publish the configured batch, then wait out the configured interval. */
    private void publishBatch() {
        for (long i = 0; i < this.messages; i++) {
            writer.writeMessage(generator.createMessage());
        }
        WorkerLoop.sleepFor(halfSeconds * 500);
    }
}
