package net.podspace.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TempScale {
    FAHRENHEIT("F"), CELSIUS("C");
    private final String scale;

    TempScale(String scale) {
        this.scale = scale;
    }

    @JsonValue
    public String getScale() {
        return this.scale;
    }
}
