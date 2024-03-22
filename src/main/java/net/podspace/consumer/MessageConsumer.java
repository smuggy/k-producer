package net.podspace.consumer;

import java.util.Optional;

public interface MessageConsumer<T extends Comparable<T>> {
    Optional<Pair<T, Integer>> getMessage(String s);
}
