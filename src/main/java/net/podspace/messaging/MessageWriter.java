package net.podspace.messaging;

public interface MessageWriter {
    void writeMessage(String message);

    /**
     * Writes with a partition key.
     *
     * <p>Kafka hashes the key to choose a partition, so a key is what makes partition placement
     * deterministic and lets per-partition behaviour be exercised on purpose rather than by
     * whatever the sticky partitioner happened to do. It is also the unit Kafka orders within:
     * ordering is guaranteed inside a partition, so same-key messages stay in order and
     * different-key messages need not.
     *
     * <p>Defaults to ignoring the key, because only Kafka has the concept - the in-memory queue
     * and the no-op transports have a single channel and nothing to partition across.
     */
    default void writeMessage(String key, String message) {
        writeMessage(message);
    }
}
