package net.podspace.domain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.pipeline.DeliveryLedger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keys are what make partition placement deliberate: Kafka hashes the key to pick a partition and
 * orders only within a partition, so with keys an out-of-order arrival is a real finding rather
 * than the expected result of spreading traffic.
 */
class TemperatureGeneratorKeyTest {

    private static TemperatureGenerator generator(int keyCount) {
        return new TemperatureGenerator(new DeliveryLedger(new SimpleMeterRegistry()), keyCount);
    }

    @Test
    void zeroKeyCountSendsUnkeyed() {
        TemperatureGenerator g = generator(0);
        for (int i = 0; i < 10; i++) {
            Assertions.assertNull(g.createMessage().key(),
                    "unkeyed leaves placement to the sticky partitioner");
        }
    }

    @Test
    void keysCycleOverExactlyTheRequestedCardinality() {
        TemperatureGenerator g = generator(4);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            keys.add(g.createMessage().key());
        }
        Assertions.assertEquals(4, keys.size(), "should use exactly keyCount distinct keys");
        Assertions.assertTrue(keys.stream().noneMatch(k -> k == null));
    }

    @Test
    void keysAreSpreadEvenlySoPartitionsAreExercisedEqually() {
        TemperatureGenerator g = generator(5);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            counts.merge(g.createMessage().key(), 1, Integer::sum);
        }
        Assertions.assertEquals(5, counts.size());
        counts.forEach((k, n) -> Assertions.assertEquals(100, n,
                "key " + k + " should get an equal share, got " + n));
    }

    @Test
    void aSingleKeyPinsEverythingToOnePartition() {
        // Useful on purpose: with one key every message lands on one partition, so Kafka's
        // ordering guarantee applies to the whole run and any reordering is a genuine fault.
        TemperatureGenerator g = generator(1);
        for (int i = 0; i < 20; i++) {
            Assertions.assertEquals("k0", g.createMessage().key());
        }
    }

    @Test
    void aNegativeKeyCountIsTreatedAsUnkeyedRatherThanCrashing() {
        Assertions.assertNull(generator(-3).createMessage().key());
    }
}
