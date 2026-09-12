package net.podspace.config;

public class MyBean {
    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        System.out.println("==> Setting " + value + " in bean");
        this.value = value;
    }
}
