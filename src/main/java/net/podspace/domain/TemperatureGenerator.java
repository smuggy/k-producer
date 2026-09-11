package net.podspace.domain;

import net.podspace.producer.generator.MessageGenerator;

import java.util.Random;

public class TemperatureGenerator implements MessageGenerator {
    private static final Random RANDOM = new Random();
    private static final int MAX_FILLER_SIZE = 1_000_000;
    private static final int LEFT_LIMIT = 48;   // numeral '0'
    private static final int RIGHT_LIMIT = 122; // letter 'z'
    // Set from request threads via /publisher/raisefillersize, read by the publisher worker
    // thread; volatile supplies the happens-before edge.
    private volatile int fillerSize = 0;

    public String createMessage() {
        Temperature t = Temperature.createCelsiusTemp(RANDOM.nextDouble(100));
        t.setFiller(generateFiller());
        return t.toJsonString();
    }

    public int getFillerSize() {
        return fillerSize;
    }

    public void setFillerSize(int size) {
        if (size < 0) {
            fillerSize = 0;
        } else {
            fillerSize = Math.min(size, MAX_FILLER_SIZE);
        }
    }

    private String generateFiller() {
        // Read the volatile field once: reading it separately for the guard and the limit would
        // let a concurrent setFillerSize() change the value in between, producing a filler that
        // does not match the size that was checked.
        int size = fillerSize;
        if (size <= 0) {
            return "";
        }

        return RANDOM.ints(LEFT_LIMIT, RIGHT_LIMIT + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(size)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
