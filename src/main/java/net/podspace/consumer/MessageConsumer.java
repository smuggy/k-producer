package net.podspace.consumer;

import java.util.Optional;

public interface MessageConsumer<T> {
    Optional<T> getMessage(String s);
}
