package net.podspace.domain;

import net.podspace.producer.generator.MessageGenerator;

import java.util.Random;

public class TemperatureGenerator implements MessageGenerator {
    public String createMessage() {
        Random r = new Random();
        Temperature t = Temperature.createCelsiusTemp(r.nextDouble(100));
        return t.toJsonString();
    }

    public int getFillerSize() {
        return Temperature.getFillerSize();
    }

    public void setFillerSize(int size) {
        Temperature.setFillerSize(size);
    }
}
