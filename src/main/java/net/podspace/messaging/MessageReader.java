package net.podspace.messaging;

import java.util.List;

public interface MessageReader {
    List<String> readMessage();
    default void close() {}
}
