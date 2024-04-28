package net.podspace.producer.generator;

public interface PublisherManager {
    void resume();

    void pause();

    void quit();

    long getSleep();

    void setSleep(long seconds);

    long getMessages();

    void setMessages(long count);

    int getFillerSize();

    void setFillerSize(int size);
}
