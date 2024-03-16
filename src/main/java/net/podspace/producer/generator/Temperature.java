package net.podspace.producer.generator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TempScale scale;
    private final LocalDateTime time;
    private final double temp;
    private final String time_id;
    private static int fillerSize = 0;

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
        this.time_id = UUID.randomUUID().toString();
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
                "\"id\":\"" + time_id +
                "\",\"temp\":" + temp +
                ",\"time\":\"" + time.format(formatter) +
                "\",\"scale\":\"" + scale.getScale() +
                "\",\"filler\":\"" + generateFiller() + "\"}";
    }
}
