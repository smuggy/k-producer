package net.podspace.messaging.noop;

import net.podspace.messaging.MessageWriter;

import java.nio.charset.StandardCharsets;
public class ConsoleWriter implements MessageWriter {
    @Override
    public void writeMessage(byte[] message) {
        // Decoded for display only. Avro will not render legibly, which is expected - this
        // transport exists to eyeball JSON without a broker.
        System.out.println(new String(message, StandardCharsets.UTF_8));
    }
}
