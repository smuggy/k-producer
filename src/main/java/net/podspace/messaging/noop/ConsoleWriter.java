package net.podspace.messaging.noop;

import net.podspace.messaging.MessageWriter;
public class ConsoleWriter implements MessageWriter {
    @Override
    public void writeMessage(String message) {
        System.out.println(message);
    }
}
