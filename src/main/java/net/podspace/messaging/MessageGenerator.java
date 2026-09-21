package net.podspace.messaging;

public interface MessageGenerator {
    String createMessage();
    void setFillerSize(int size);
    int getFillerSize();

    /**
     * Applies a relative change and returns the resulting size, as one atomic step.
     *
     * <p>Callers adjusting by a delta must use this rather than get-then-set: two concurrent
     * requests reading the same value and writing back their own increment lose one of the two
     * updates.
     */
    int adjustFillerSize(int delta);
}
