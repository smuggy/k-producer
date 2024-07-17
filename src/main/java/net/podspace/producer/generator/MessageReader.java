package net.podspace.producer.generator;

import java.util.List;

public interface MessageReader {
    List<String> readMessage();
    void close();
}
