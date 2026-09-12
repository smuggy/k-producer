package net.podspace.messaging;

public record Pair<T extends Comparable<T>, U>(T a, U b) implements Comparable<Pair<T, U>> {
    @Override
    public int compareTo(Pair<T, U> other) {
        return this.a.compareTo(other.a);
    }
}
