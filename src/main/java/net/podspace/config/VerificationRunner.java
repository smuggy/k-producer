package net.podspace.config;

import net.podspace.domain.Temperature;
import net.podspace.pipeline.DeliveryLedger;
import net.podspace.pipeline.Publisher;
import net.podspace.pipeline.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Runs a fixed number of messages, reconciles, and exits with a status code - so the probe can be
 * a build step rather than something a person watches.
 *
 * <p>The interactive endpoints answer "what is happening now"; this answers "did it pass", which
 * is what a pipeline gate needs. Use it after a cluster upgrade or a configuration change to prove
 * nothing is being dropped before traffic is moved.
 *
 * <p>Exit codes are deliberately three-valued rather than pass/fail:
 * <ul>
 *   <li><b>0</b> - every message published was delivered.</li>
 *   <li><b>1</b> - messages were lost. The cluster was given them and did not deliver them.</li>
 *   <li><b>2</b> - inconclusive; the run could not be completed, so no verdict is claimed. A
 *       failed CI job that says "lost 4 messages" and one that says "could not reach the brokers"
 *       call for different responses, and collapsing them into one failure code hides that.</li>
 * </ul>
 *
 * <p>Only meaningful where one process both publishes and consumes - the loopback role, or origin
 * with an echo instance running. An echo instance has no publisher to drive.
 */
public class VerificationRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(VerificationRunner.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    static final int PASS = 0;
    static final int LOSS = 1;
    static final int INCONCLUSIVE = 2;

    private final Publisher publisher;
    private final Watcher<Temperature> watcher;
    private final DeliveryLedger ledger;
    private final ConfigurableApplicationContext context;
    private final long targetMessages;
    private final Duration publishTimeout;
    private final Duration drainTimeout;
    private final Duration attachTimeout;

    public VerificationRunner(Publisher publisher, Watcher<Temperature> watcher,
                              DeliveryLedger ledger, ConfigurableApplicationContext context,
                              long targetMessages, Duration publishTimeout, Duration drainTimeout,
                              Duration attachTimeout) {
        this.publisher = publisher;
        this.watcher = watcher;
        this.ledger = ledger;
        this.context = context;
        this.targetMessages = targetMessages;
        this.publishTimeout = publishTimeout;
        this.drainTimeout = drainTimeout;
        this.attachTimeout = attachTimeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = execute();
        // Closes the context first so the Kafka clients and worker threads shut down cleanly,
        // then exits with the verdict.
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** Package-private so the verdict logic can be tested without exiting the JVM. */
    int execute() {
        logger.info("Verification run: target {} messages, publish timeout {}s, drain timeout {}s",
                targetMessages, publishTimeout.toSeconds(), drainTimeout.toSeconds());

        // Consumer first, and then WAIT for it to hold a partition assignment. Ordering the calls
        // is not enough: assignment happens on the consumer's first poll, so publishing
        // immediately after initiate() puts the opening batch out of reach when autoOffsetReset is
        // latest, and it is then reported as loss that never happened. Observed as exactly one
        // batch missing on every run before this wait existed.
        watcher.initiate();
        if (!await(watcher::isAttached, attachTimeout)) {
            logger.error("Consumer did not receive a partition assignment within {}s - not "
                    + "publishing, because anything sent now would be missed and read as loss.",
                    attachTimeout.toSeconds());
            watcher.teardown();
            return INCONCLUSIVE;
        }
        publisher.initiate();

        boolean reachedTarget = await(() -> ledger.snapshot().produced() >= targetMessages,
                publishTimeout);
        publisher.teardown();

        // Everything issued is now either received, retired as unsent, or genuinely in flight.
        boolean drained = await(() -> ledger.snapshot().pending() == 0, drainTimeout);
        watcher.teardown();
        if (drained) {
            // Settles anything the sliding window has not yet judged. Only safe once drained:
            // finalizing while messages are still in flight would report them as lost.
            ledger.finalizeOutstanding();
        }

        DeliveryLedger.Snapshot s = ledger.snapshot();
        int code = verdict(s, reachedTarget, drained);
        report(s, code);
        return code;
    }

    /** Package-private for tests: the verdict is the part worth pinning down. */
    static int verdict(DeliveryLedger.Snapshot s, boolean reachedTarget, boolean drained) {
        if (!reachedTarget) {
            return INCONCLUSIVE; // never managed to publish the requested volume
        }
        if (s.unsent() > 0) {
            // Sends failed locally, so the cluster was never given them. Nothing was lost, but
            // nothing was proved either.
            return INCONCLUSIVE;
        }
        if (!drained) {
            return INCONCLUSIVE; // still in flight; calling that loss would be wrong
        }
        return s.missing() > 0 ? LOSS : PASS;
    }

    private void report(DeliveryLedger.Snapshot s, int code) {
        String verdict = switch (code) {
            case PASS -> "PASS - every message published was delivered";
            case LOSS -> "FAIL - " + s.missing() + " message(s) lost";
            default -> "INCONCLUSIVE - the run did not complete, no verdict claimed";
        };
        logger.info("""
                        Verification result: {}
                          run id    {}
                          produced  {}
                          unsent    {}   (send failed locally, never reached the cluster)
                          offered   {}   (what the cluster was asked to carry)
                          received  {}
                          missing   {}
                          pending   {}
                          duplicates {}  out of order {}  foreign {}
                          exit code {}""",
                verdict, s.runId(), s.produced(), s.unsent(), s.offered(), s.received(),
                s.missing(), s.pending(), s.duplicates(), s.outOfOrder(), s.foreign(), code);
    }

    /** Polls until the condition holds or the budget runs out; returns whether it held. */
    private static boolean await(BooleanSupplier condition, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
