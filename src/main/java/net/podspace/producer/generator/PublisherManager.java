package net.podspace.producer.generator;

public interface PublisherManager {
    void resume();
    void pause();
    void quit();
    void setSleep(long seconds);
    long getSleep();
    void setMessages(long count);
    long getMessages();
    void setFillerSize(int size);
    int getFillerSize();
}
