package net.podspace.producer.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.podspace.domain.TempScale;
import net.podspace.domain.Temperature;
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
        System.out.println(temp.toJsonString());
        ObjectMapper om = new ObjectMapper();
        try {
            var newtemp = om.readValue(temp.toJsonString(), Temperature.class);
        } catch (JsonProcessingException jme) {
            System.out.println("Processing exception occurred.");
            System.out.println(jme);
            Assertions.fail();
        }
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
