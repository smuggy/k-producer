package net.podspace.producer.generator;

import java.util.Random;

public class TemperatureProducer implements MessageProducer {
    public String createMessage() {
        Random r = new Random();
        Temperature t = Temperature.createCelsiusTemp(r.nextDouble(100));
        return t.toJsonString();
    }
}
