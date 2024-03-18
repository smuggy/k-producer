package net.podspace.producer.generator;

public interface MessageGenerator {
    String createMessage();
    void setFillerSize(int size);
    int getFillerSize();
}
