package net.podspace.domain;

import net.podspace.messaging.MessageGenerator;
import net.podspace.messaging.MessageGenerator.Generated;
import net.podspace.pipeline.DeliveryLedger;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class TemperatureGenerator implements MessageGenerator {
    private static final Random RANDOM = new Random();
    private static final int MAX_FILLER_SIZE = 1_000_000;
    private static final int LEFT_LIMIT = 48;   // numeral '0'
    private static final int RIGHT_LIMIT = 122; // letter 'z'
    // Set from request threads via /publisher/raisefillersize, read by the publisher worker
    // thread. Atomic rather than volatile: volatile makes each read and each write atomic, but
    // not the read-modify-write that a relative adjustment needs.
    private final AtomicInteger fillerSize = new AtomicInteger();
    private final DeliveryLedger ledger;

    public TemperatureGenerator(DeliveryLedger ledger) {
        this.ledger = ledger;
    }

    public Generated createMessage() {
        // Drawing the sequence from the ledger is what records the message as produced - there is
        // no separate bookkeeping call that could be forgotten or double-counted. It is handed
        // back so the publisher can retire it if the send never reaches the cluster.
        long sequence = ledger.nextSequence();
        Temperature t = Temperature.createCelsiusTemp(
                RANDOM.nextDouble(100), generateFiller(), ledger.getRunId(), sequence);
        return new Generated(t.toJsonString(), sequence);
    }

    public int getFillerSize() {
        return fillerSize.get();
    }

    public void setFillerSize(int size) {
        fillerSize.set(clamp(size));
    }

    @Override
    public int adjustFillerSize(int delta) {
        // Widen before adding: current + delta overflows int for a large delta, wrapping negative
        // and clamping to zero - so raising the size by a huge amount would silently set it to
        // nothing instead of saturating at the cap.
        return fillerSize.updateAndGet(current -> clamp((long) current + delta));
    }

    /** Keeps the size inside [0, MAX_FILLER_SIZE]; shared by the absolute and relative setters. */
    private static int clamp(long size) {
        if (size < 0) {
            return 0;
        }
        return (int) Math.min(size, MAX_FILLER_SIZE);
    }

    private String generateFiller() {
        // Read once: reading separately for the guard and the limit would let a concurrent
        // adjustment change the value in between, producing a filler that does not match the
        // size that was checked.
        int size = fillerSize.get();
        if (size <= 0) {
            return "";
        }

        return RANDOM.ints(LEFT_LIMIT, RIGHT_LIMIT + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(size)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
