package test;

public class TestJmx {
    public static void main(String [] args) {
        System.out.println("Hello");
        try {
            Thread.sleep(60L * 1000);
        } catch(InterruptedException ignored) {}
        System.out.println("World!");
    }
}
