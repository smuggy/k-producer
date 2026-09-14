package net.podspace.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TemperatureTest {
    @Test
    public void TestCelsiusCreation() {
        var temp = Temperature.createCelsiusTemp(33, "", "test-run", 7);
        Assertions.assertEquals(33, temp.getTemp());
        Assertions.assertEquals(TempScale.CELSIUS, temp.getScale());
    }
    @Test
    public void TestFahrenheitCreation() {
        var temp = Temperature.createFahrenheitTemp(33, "");
        Assertions.assertEquals(33, temp.getTemp());
        Assertions.assertEquals(TempScale.FAHRENHEIT, temp.getScale());
    }

    @Test
    public void TestValidJson() {
        var temp = Temperature.createFahrenheitTemp(33, "");
        System.out.print(temp.toJsonString());
        ObjectMapper om = new ObjectMapper();
        try {
            om.readValue(temp.toJsonString(), Temperature.class);
        } catch (JacksonException jme) {
            System.out.print("Processing exception occurred.");
            System.out.print(jme);
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
        } catch (JacksonException jpe) {
            System.out.println("Invalid json provided " + json);
        }

        return valid;
    }
}
