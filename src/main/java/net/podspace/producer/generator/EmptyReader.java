package net.podspace.producer.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class EmptyReader implements MessageReader{
    private static final Logger logger = LoggerFactory.getLogger(EmptyWriter.class);
    public EmptyReader() {
        logger.info("in empty reader constructor");
    }
    @Override
    public List<String> readMessage() {
        try {
            Thread.sleep(Duration.ofSeconds(5));
        } catch (InterruptedException ignored){}
        return new ArrayList<>();
    }
    public void close() {
    }
}
