package net.podspace.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * This class represents the temperature at a moment in time.
 */

public class Temperature implements Comparable<Temperature> {
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

    Temperature(double temp, TempScale scale, String filler) {
        this.temp = temp;
        // ISO-8601 UTC. A local, zoneless timestamp is ambiguous the moment producer and
        // consumer sit in different zones - it would silently misreport latency by hours.
        this.time = Instant.now().toString();
        this.scale = scale;
        this.timeId = UUID.randomUUID().toString();
        setFiller(filler);
    }

    public static Temperature createCelsiusTemp(double temp, String filler) {
        return new Temperature(temp, TempScale.CELSIUS, filler);
    }

    public static Temperature createFahrenheitTemp(double temp, String filler) {
        return new Temperature(temp, TempScale.FAHRENHEIT, filler);
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
        return Instant.parse(this.time).compareTo(Instant.parse(other.time));
    }
}
