package net.podspace.producer.generator;

public class ConsoleWriter implements MessageWriter {
    @Override
    public void writeMessage(String message) {
        System.out.println(message);
    }
}
