package net.podspace.producer.generator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TempScale scale;
    private final LocalDateTime time;
    private final double temp;


    public static Temperature createCelsiusTemp(double temp) {
        return new Temperature(temp, TempScale.CELSIUS);
    }
    public static Temperature createFahrenheitTemp(double temp) {
        return new Temperature(temp, TempScale.FAHRENHEIT);
    }

    Temperature(double temp, TempScale scale) {
        this.temp = temp;
        this.time = LocalDateTime.now();
        this.scale = scale;
    }

    public TempScale getScale() {
        return scale;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public double getTemp() {
        return temp;
    }

    public String toJsonString() {
        return "{" +
                "\"temp\":" + temp +
                ",\"time\":\"" +
                time.format(formatter) +
                "\",\"scale\":\"" +
                scale.getScale() + "\"}";
    }
}
