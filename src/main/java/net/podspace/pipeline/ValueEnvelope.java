package net.podspace.pipeline;

/**
 * A consumed message paired with the time it was read, so end-to-end latency can be derived.
 *
 * @param item the parsed message
 * @param time when it was read, in the shared timestamp format
 * @param size the serialized size of the message that produced it
 */
public record ValueEnvelope<T>(T item, String time, int size) {
}
