package net.podspace.config;

public class MyBean {
    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        System.out.print("==> Setting " + value + " in bean\n");
        this.value = value;
    }
}
