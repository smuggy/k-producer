package net.podspace.consumer;

public class Pair<T extends Comparable<T>, U> implements Comparable<Pair<T,U>> {
    public T a;
    public U b;

    public Pair(){}
    public Pair(T a, U b) {
        this.a = a;
        this.b = b;
    }

    public int compareTo(Pair<T,U> other) {
        return this.a.compareTo(other.a);
    }
}
