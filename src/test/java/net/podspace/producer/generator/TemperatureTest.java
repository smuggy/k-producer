package net.podspace.producer.generator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TemperatureTest {
    @Test
    public void TestCelsiusCreation() {
        var temp = Temperature.createCelsiusTemp(33);
        Assertions.assertEquals(temp.getTemp(), 33);
        Assertions.assertEquals(temp.getScale(), TempScale.CELSIUS);
    }
    @Test
    public void TestFahrenheitCreation() {
        var temp = Temperature.createFahrenheitTemp(33);
        Assertions.assertEquals(temp.getTemp(), 33);
        Assertions.assertEquals(temp.getScale(), TempScale.FAHRENHEIT);
    }
}
