package net.podspace.producer.generator;

public interface GeneratorManager {
    void resume();
    void pause();
    void quit();
    void setSeconds(long seconds);
}
