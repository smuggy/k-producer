package net.podspace.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature implements Comparable<Temperature> {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    @JsonProperty("scale")
    private TempScale scale;
    @JsonProperty("time")
    private String time;
    @JsonProperty("temp")
    private double temp;
    @JsonProperty("id")
    private String timeId;
    @JsonProperty("filler")
    private String filler = "";

    public Temperature() {
    }

    Temperature(double temp, TempScale scale) {
        this.temp = temp;
        this.time = LocalDateTime.now().format(formatter);
        this.scale = scale;
        this.timeId = UUID.randomUUID().toString();
    }

    public static Temperature createCelsiusTemp(double temp) {
        return new Temperature(temp, TempScale.CELSIUS);
    }

    public static Temperature createFahrenheitTemp(double temp) {
        return new Temperature(temp, TempScale.FAHRENHEIT);
    }

    public String getFiller() {
        return filler;
    }

    public void setFiller(String filler) {
        this.filler = (filler == null) ? "" : filler;
    }

    public String getTimeId() {
        return timeId;
    }

    public TempScale getScale() {
        return scale;
    }

    public String getTime() {
        return time;
    }

    public double getTemp() {
        return temp;
    }

    public String toJsonString() {
        return "{" +
                "\"id\":\"" + timeId +
                "\",\"temp\":" + temp +
                ",\"time\":\"" + time +
                "\",\"scale\":\"" + scale.getScale() +
                "\",\"filler\":\"" + filler + "\"}";
    }

    @Override
    public String toString() {
        return toJsonString();
    }

    @Override
    public int compareTo(Temperature other) {
        return LocalDateTime.parse(this.time, formatter).
                compareTo(LocalDateTime.parse(other.time, formatter));
    }
}
