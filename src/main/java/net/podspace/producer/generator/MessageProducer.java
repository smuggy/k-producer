package net.podspace.producer.generator;

public interface MessageProducer {
    String createMessage();
    void setFillerSize(int size);
    int getFillerSize();
}
