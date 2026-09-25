package net.podspace.messaging;

import java.util.Optional;

/**
 * Decodes a message off the wire.
 *
 * <p>Takes bytes because the encoding is not fixed: the same topic can carry JSON and Avro at once
 * during a format migration, and an implementation is expected to work out which it has rather
 * than be told. The two are unambiguous - Confluent-framed Avro begins with a 0x00 magic byte,
 * JSON with '{' - so a consumer configured for the wrong format would otherwise silently discard
 * every message.
 */
public interface MessageConsumer<T extends Comparable<T>> {
    Optional<Pair<T, Integer>> getMessage(byte[] message);
}
