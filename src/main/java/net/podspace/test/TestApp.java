package net.podspace.test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TestApp {
    private static final DateTimeFormatter formatter_one = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter formatter_two = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter formatter_three = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    public static void main(String [] args) {
        System.out.println("hello world.");
        var n = LocalDateTime.now();
        System.out.println(n.format(formatter_two));
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException ignored) {}
        LocalDateTime current = LocalDateTime.now();
        System.out.println(current.format(formatter_two));
        Duration d = Duration.between(n, current);
        System.out.println(d.toSeconds());
        System.out.println(d.getNano()/1_000);
        double mm = d.toSeconds() + ((double)d.getNano())/1_000_000_000.00;
        System.out.println(mm);
    }
}
