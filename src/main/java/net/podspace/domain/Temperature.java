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
    private String tempId;
    @JsonProperty("filler")
    private String filler = "";
    /** Identifies the publisher run, so a consumer can ignore messages left by earlier runs. */
    @JsonProperty("run")
    private String run = "";
    /** Monotonic within a run; the basis for detecting gaps, duplicates and reordering. */
    @JsonProperty("seq")
    private long seq = -1;

    public Temperature() {
    }

    Temperature(double temp, TempScale scale, String filler, String run, long seq) {
        this.run = (run == null) ? "" : run;
        this.seq = seq;
        this.temp = temp;
        // ISO-8601 UTC. A local, zoneless timestamp is ambiguous the moment producer and
        // consumer sit in different zones - it would silently misreport latency by hours.
        this.time = Instant.now().toString();
        this.scale = scale;
        this.tempId = UUID.randomUUID().toString();
        setFiller(filler);
    }

    public static Temperature createCelsiusTemp(double temp, String filler, String run, long seq) {
        return new Temperature(temp, TempScale.CELSIUS, filler, run, seq);
    }

    public static Temperature createFahrenheitTemp(double temp, String filler) {
        return new Temperature(temp, TempScale.FAHRENHEIT, filler, "", -1);
    }

    /**
     * Rebuilds a message that arrived off the topic, preserving the values it was created with.
     *
     * <p>Distinct from the create* factories on purpose: those stamp a fresh timestamp and id,
     * which is exactly wrong for a message being decoded - latency is measured against the
     * ORIGINAL timestamp, so regenerating it here would zero out the very thing being measured.
     * Jackson reaches the fields directly, so only non-Jackson decoders need this.
     */
    public static Temperature received(String tempId, double temp, String time, TempScale scale,
                                       String run, long seq, String filler) {
        Temperature t = new Temperature();
        t.tempId = tempId;
        t.temp = temp;
        t.time = time;
        t.scale = scale;
        t.run = (run == null) ? "" : run;
        t.seq = seq;
        t.filler = (filler == null) ? "" : filler;
        return t;
    }

    public String getRun() {
        return run;
    }

    public long getSeq() {
        return seq;
    }

    public String getFiller() {
        return filler;
    }

    public void setFiller(String filler) {
        this.filler = (filler == null) ? "" : filler;
    }

    public String getTempId() {
        return tempId;
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
                "\"id\":\"" + tempId +
                "\",\"temp\":" + temp +
                ",\"time\":\"" + time +
                "\",\"scale\":\"" + scale.getScale() +
                "\",\"run\":\"" + run +
                "\",\"seq\":" + seq +
                ",\"filler\":\"" + filler + "\"}";
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
