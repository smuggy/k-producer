package net.podspace.producer.generator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TempScale scale;
    private LocalDateTime time;
    private double temp;


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
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"temp\":");
        sb.append(temp);
        sb.append("\"time\":\"");
        sb.append(time.format(formatter));
        sb.append("\",\"scale\":\"");
        sb.append(scale.getScale());
        sb.append("\"}");
        return sb.toString();
    }

}
