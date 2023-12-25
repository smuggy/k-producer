package net.podspace.producer.generator;

public enum TempScale {
    FAHRENHEIT("F"), CELSIUS("C");
    private final String scale;

    TempScale(String scale){
        this.scale = scale;
    }

    public String getScale() {
        return this.scale;
    }
}
