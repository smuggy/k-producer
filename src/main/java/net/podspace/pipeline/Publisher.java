package net.podspace.pipeline;

import net.podspace.messaging.MessageGenerator;
import net.podspace.messaging.MessageWriter;

import java.util.concurrent.atomic.AtomicLong;

public class Publisher implements PublisherManager {
    private static final long PAUSE_MILLIS = 10_000;
    private final MessageGenerator generator;
    private final MessageWriter writer;
    private final WorkerLoop loop;
    // Written by request threads (PublisherController / JMX), read by the publisher worker thread.
    // Atomic rather than volatile: volatile makes each individual read and write atomic, but the
    // REST surface adjusts these by a delta, and a get-then-set pair is not atomic as a unit. Two
    // concurrent /raisesleep calls against volatile fields could both read the same value and both
    // write value+1, losing one increment. The adjust* methods below close that.
    private final AtomicLong halfSeconds = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();

    public Publisher(MessageGenerator generator, MessageWriter writer) {
        this.generator = generator;
        this.writer = writer;
        this.messages.set(1);
        this.halfSeconds.set(10);
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
        return halfSeconds.get();
    }

    @Override
    public void setSleep(long halfSeconds) {
        this.halfSeconds.set(clampSleep(halfSeconds));
    }

    /** Applies a relative change to the interval and returns the new value, atomically. */
    public long adjustSleep(long delta) {
        return halfSeconds.updateAndGet(current -> clampSleep(current + delta));
    }

    private static long clampSleep(long value) {
        if (value < 0) {
            return 5;
        }
        return value == 0 ? 1 : value;
    }

    @Override
    public int getFillerSize() {
        return generator.getFillerSize();
    }

    @Override
    public void setFillerSize(int size) {
        generator.setFillerSize(size);
    }

    /** Applies a relative change to the filler size and returns the new value, atomically. */
    public int adjustFillerSize(int delta) {
        return generator.adjustFillerSize(delta);
    }

    @Override
    public long getMessages() {
        return messages.get();
    }

    @Override
    public void setMessages(long count) {
        messages.set(Math.max(1, count));
    }

    /** Applies a relative change to the batch size and returns the new value, atomically. */
    public long adjustMessages(long delta) {
        return messages.updateAndGet(current -> Math.max(1, current + delta));
    }

    /** One pass: publish the configured batch, then wait out the configured interval. */
    private void publishBatch() {
        // Read each once, so a concurrent adjustment cannot change the batch size midway through
        // the loop or split the interval across two different values.
        long batch = messages.get();
        for (long i = 0; i < batch; i++) {
            writer.writeMessage(generator.createMessage());
        }
        WorkerLoop.sleepFor(halfSeconds.get() * 500);
    }
}
