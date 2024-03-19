package net.podspace.producer.generator;

public interface PublisherManager {
    void resume();
    void pause();
    void quit();
    void setSeconds(long seconds);
    long getSeconds();
    void setMessages(long count);
    long getMessages();
    void setFillerSize(int size);
    int getFillerSize();
}