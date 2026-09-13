package net.podspace.messaging;

import java.util.List;

public interface MessageReader {
    List<String> readMessage();

    default void close() {}

    /**
     * Whether this reader is currently in a position to receive anything.
     *
     * <p>Defaults to true: most transports have no notion of connectivity, and a reader that
     * simply has nothing to hand over is not unhealthy. Transports that can be attached or
     * detached - a Kafka consumer holding, or not holding, a partition assignment - override this
     * so health reporting can tell "idle" apart from "not connected".
     */
    default boolean isReady() {
        return true;
    }
}
