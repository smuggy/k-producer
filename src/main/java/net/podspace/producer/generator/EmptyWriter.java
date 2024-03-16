package net.podspace.producer.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmptyWriter implements MessageWriter {
    private static final Logger logger = LoggerFactory.getLogger(EmptyWriter.class);
    private int count;
    public EmptyWriter() {
        count = 0;
    }
    @Override
    public void writeMessage(String message) {
        logger.info("Writing message number " + count++);
    }
}
