package net.podspace.messaging;

public interface MessageGenerator {

    /**
     * A generated message together with the delivery sequence embedded in it.
     *
     * <p>The sequence is returned rather than left implicit because the caller has to be able to
     * retire it if the send fails. Reading it back off the generator afterwards would work only
     * while exactly one thread publishes, which is an invariant nothing in the type system keeps.
     */
    record Generated(String key, String payload, long sequence) {
        /** For generators that do not take part in delivery reconciliation. */
        public static final long NO_SEQUENCE = -1L;

        /** No key and no sequence: an unkeyed message outside delivery reconciliation. */
        public static Generated untracked(String payload) {
            return new Generated(null, payload, NO_SEQUENCE);
        }
    }

    Generated createMessage();
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
