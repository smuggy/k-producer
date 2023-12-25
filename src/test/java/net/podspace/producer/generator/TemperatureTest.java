package net.podspace.producer.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    public void TestValidJson() {
        var temp = Temperature.createFahrenheitTemp(33);
        Assertions.assertTrue(isValidJSON(temp.toJsonString()));
    }

    public boolean isValidJSON(final String json) {
        boolean valid = false;
        try {
            ObjectMapper om = new ObjectMapper();
            om.readTree(json);
            valid = true;
        } catch (JsonProcessingException jpe) {
            System.out.println("Invalid json provided " + json);
        }

        return valid;
    }
}
