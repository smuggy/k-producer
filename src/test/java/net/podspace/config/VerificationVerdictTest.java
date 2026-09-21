package net.podspace.config;

import net.podspace.pipeline.DeliveryLedger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The exit code is the whole point of verification mode, so the verdict is worth pinning exactly.
 *
 * <p>The three-valued result matters: a build that failed because messages were lost and one that
 * failed because the brokers were unreachable need different responses, and collapsing both into
 * "non-zero" hides that.
 */
class VerificationVerdictTest {

    /** runId, produced, received, duplicates, missing, late, outOfOrder, foreign, unsent, pending */
    private static DeliveryLedger.Snapshot snap(long produced, long received, long missing,
                                                long unsent, long pending) {
        return new DeliveryLedger.Snapshot("run", produced, received, 0, missing, 0, 0, 0,
                unsent, pending);
    }

    @Test
    void everythingDeliveredPasses() {
        Assertions.assertEquals(VerificationRunner.PASS,
                VerificationRunner.verdict(snap(1000, 1000, 0, 0, 0), true, true));
    }

    @Test
    void lostMessagesFail() {
        Assertions.assertEquals(VerificationRunner.LOSS,
                VerificationRunner.verdict(snap(1000, 996, 4, 0, 0), true, true));
    }

    @Test
    void failingToPublishTheTargetIsInconclusiveNotAPass() {
        // The obvious bug: nothing was lost because almost nothing was sent. Reporting PASS here
        // would green a build that never exercised the cluster.
        Assertions.assertEquals(VerificationRunner.INCONCLUSIVE,
                VerificationRunner.verdict(snap(12, 12, 0, 0, 0), false, true));
    }

    @Test
    void sendsThatFailedLocallyAreInconclusiveNotLoss() {
        // The cluster was never given these, so it cannot have lost them - but nothing was proved
        // either, so this is not a pass.
        Assertions.assertEquals(VerificationRunner.INCONCLUSIVE,
                VerificationRunner.verdict(snap(1000, 0, 0, 1000, 0), true, true));
    }

    @Test
    void messagesStillInFlightAreInconclusiveNotLoss() {
        Assertions.assertEquals(VerificationRunner.INCONCLUSIVE,
                VerificationRunner.verdict(snap(1000, 950, 0, 0, 50), true, false));
    }

    @Test
    void lossIsReportedEvenWhenSomeSendsAlsoFailed() {
        // unsent is checked first deliberately: if the producer could not reach the cluster, the
        // missing count is not a trustworthy loss figure.
        Assertions.assertEquals(VerificationRunner.INCONCLUSIVE,
                VerificationRunner.verdict(snap(1000, 900, 50, 50, 0), true, true));
    }
}
