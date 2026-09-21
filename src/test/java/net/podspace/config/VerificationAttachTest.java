package net.podspace.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
import net.podspace.domain.TemperatureGenerator;
import net.podspace.messaging.MessageReader;
import net.podspace.pipeline.DeliveryLedger;
import net.podspace.pipeline.Publisher;
import net.podspace.pipeline.Watcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verification must not publish before the consumer holds a partition assignment.
 *
 * <p>Assignment happens on the consumer's first poll, so starting the publisher immediately after
 * initiate() puts the opening batch beyond reach when autoOffsetReset is latest - and it is then
 * counted as loss that never happened. This shipped once and cost exactly one batch on every run.
 */
class VerificationAttachTest {

    /** Never attaches, the way a consumer against unreachable brokers behaves. */
    private static class NeverAttachedReader implements MessageReader {
        @Override public List<String> readMessage() { return Collections.emptyList(); }
        @Override public boolean isReady() { return false; }
    }

    @Test
    void doesNotPublishWhenTheConsumerNeverAttaches() {
        DeliveryLedger ledger = new DeliveryLedger(new SimpleMeterRegistry());
        AtomicInteger written = new AtomicInteger();
        Publisher publisher = new Publisher(new TemperatureGenerator(ledger),
                m -> written.incrementAndGet(), ledger);
        Watcher<Temperature> watcher = new Watcher<>(new TemperatureConsumer(),
                new NeverAttachedReader());

        VerificationRunner runner = new VerificationRunner(publisher, watcher, ledger,
                null, 100, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMillis(500));

        int code = runner.execute();

        Assertions.assertEquals(VerificationRunner.INCONCLUSIVE, code,
                "an unattached consumer means no verdict is possible");
        Assertions.assertEquals(0, written.get(),
                "nothing may be published into a void and then counted as lost");
        Assertions.assertEquals(0, ledger.snapshot().produced(),
                "no sequence should even be issued");
    }
}
