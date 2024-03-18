package net.podspace.test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TestApp {
    private static final DateTimeFormatter formatter_one = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter formatter_two = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter formatter_three = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String [] args) {
        System.out.println("hello world.");
        var n = LocalDateTime.now();
        System.out.println(n.format(formatter_one));
        System.out.println(n.format(formatter_two));
        System.out.println(n.format(formatter_three));
    }
}
