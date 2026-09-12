package net.podspace.messaging;

public interface MessageGenerator {
    String createMessage();
    void setFillerSize(int size);
    int getFillerSize();
}
