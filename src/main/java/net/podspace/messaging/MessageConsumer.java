package net.podspace.messaging;

import java.util.Optional;

public interface MessageConsumer<T extends Comparable<T>> {
    Optional<Pair<T, Integer>> getMessage(String s);
}
