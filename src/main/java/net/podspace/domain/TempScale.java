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

    /**
     * Resolves the wire symbol ("C"/"F") back to a constant. Needed by any decoder that is not
     * Jackson - Avro carries the symbol, not the enum name.
     */
    public static TempScale fromSymbol(String symbol) {
        for (TempScale s : values()) {
            if (s.scale.equalsIgnoreCase(symbol)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown temperature scale: " + symbol);
    }
}
