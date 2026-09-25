package net.podspace.messaging;

/**
 * Writes an already-encoded message to the transport.
 *
 * <p>Bytes rather than String: the payload may be Avro, whose binary encoding is not valid UTF-8.
 * Round-tripping it through a Java String replaces the invalid sequences with U+FFFD and changes
 * the length - measured at 89 bytes in, 95 bytes out - so a String-based transport silently
 * corrupts every Avro message. Keeping the transport byte-oriented also means the relay forwards
 * a payload it never has to interpret, which is what preserves the originating timestamp.
 */
public interface MessageWriter {
    void writeMessage(byte[] message);

    /**
     * Writes with a partition key.
     *
     * <p>Kafka hashes the key to choose a partition, so a key is what makes partition placement
     * deterministic and lets per-partition behavior be exercised on purpose rather than by
     * whatever the sticky partitioner happened to do. It is also the unit Kafka orders within:
     * ordering is guaranteed inside a partition, so same-key messages stay in order and
     * different-key messages need not.
     *
     * <p>Defaults to ignoring the key, because only Kafka has the concept - the in-memory queue
     * and the no-op transports have a single channel and nothing to partition across.
     */
    default void writeMessage(String key, byte[] message) {
        writeMessage(message);
    }
}
