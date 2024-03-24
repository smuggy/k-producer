package net.podspace.test;

import net.podspace.producer.ConsumerController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TestApp {
    private static final DateTimeFormatter formatter_one = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter formatter_two = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter formatter_three = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    private static final List<Double> ld = new ArrayList<>();

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

        ld.add(3.14);
        ld.add(2.71);
        ld.add(15.03);
        ld.add(10.33);
        ld.add(4.55);
        ld.add(13.64);
//        ld.add(20.00);
        ld.add(12.00);
        ld.add(1.22);
        ld.add(0.00);
//        ld.add(19.99);
        ld.add(11.22);
        ld.add(11.23);
        ld.add(1.32);
        ld.add(13.2);

        var histogram = getHistogram();
        System.out.println(histogram);
    }

    private static class HistogramEntry {
        double upper;
        int count;
        HistogramEntry(double upper) {
            this.upper = upper;
            this.count = 0;
        }
        void increment() {
            this.count++;
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(upper).append("\t").append(count).append("\n");
            return sb.toString();
        }
    }

    private static List<HistogramEntry> getHistogram() {
        List<HistogramEntry> l = new ArrayList<>(21);
        var max = Collections.max(ld);
        var spread = max / 20;
        System.err.println("Max is: " + max);
        System.err.println("Spread is: " + spread);
        for (int j=0; j<21; j++) {
            l.add(new HistogramEntry(spread  * j + spread));
        }
        for (var i: ld) {
            int location = (int) (i / spread);
            System.err.println(location);
            if (l.get(location).upper >= i)
                l.get(location).increment();
            else
                l.get(location + 1).increment();
        }
        return l;
    }
}
