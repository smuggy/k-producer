package net.podspace.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature implements Comparable<Temperature>{
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
    private String filler;
    private static int fillerSize = 0;

    public static Temperature createCelsiusTemp(double temp) {
        return new Temperature(temp, TempScale.CELSIUS);
    }
    public static Temperature createFahrenheitTemp(double temp) {
        return new Temperature(temp, TempScale.FAHRENHEIT);
    }

    public Temperature(){}
    Temperature(double temp, TempScale scale) {
        this.temp = temp;
        this.time = LocalDateTime.now().format(formatter);
        this.scale = scale;
        this.timeId = UUID.randomUUID().toString();
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

    public static int getFillerSize() {
        return fillerSize;
    }
    public static void setFillerSize(int size) {
        if (size < 0) {
            fillerSize = 0;
        } else {
            fillerSize = Math.min(size, 1_000_000);
        }
    }
    public String generateFiller() {
        if (fillerSize > 0) {
            int leftLimit = 48; // numeral '0'
            int rightLimit = 122; // letter 'z'
            Random random = new Random();

            return random.ints(leftLimit, rightLimit + 1)
                    .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                    .limit(fillerSize)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
        }
        return "";
    }

    public String toJsonString() {
        return "{" +
                "\"id\":\"" + timeId +
                "\",\"temp\":" + temp +
                ",\"time\":\"" + time +
                "\",\"scale\":\"" + scale.getScale() +
                "\",\"filler\":\"" + generateFiller() + "\"}";
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
