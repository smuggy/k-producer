package net.podspace.domain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.pipeline.DeliveryLedger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class TemperatureGeneratorTest {

    private static TemperatureGenerator generator() {
        return new TemperatureGenerator(new DeliveryLedger(new SimpleMeterRegistry()));
    }

    @Test
    void concurrentAdjustmentsAreNotLost() throws Exception {
        TemperatureGenerator g = generator();
        var pool = Executors.newFixedThreadPool(8);
        var done = new CountDownLatch(8);
        try {
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        g.adjustFillerSize(1);
                    }
                    done.countDown();
                });
            }
            Assertions.assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        Assertions.assertEquals(8 * 500, g.getFillerSize());
    }

    @Test
    void clampsAtBothEnds() {
        TemperatureGenerator g = generator();
        g.setFillerSize(-1);
        Assertions.assertEquals(0, g.getFillerSize(), "negative sizes clamp to zero");
        g.setFillerSize(Integer.MAX_VALUE);
        Assertions.assertEquals(1_000_000, g.getFillerSize(), "oversized requests clamp to the cap");
        // The cap must hold for relative adjustment too, including on overflow.
        Assertions.assertEquals(1_000_000, g.adjustFillerSize(Integer.MAX_VALUE));
    }

    @Test
    void producedFillerMatchesTheConfiguredSize() {
        TemperatureGenerator g = generator();
        g.setFillerSize(256);
        String json = new String(g.createMessage().payload(),
                java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(json.length() >= 256, "filler should be present in the message");
    }
}
