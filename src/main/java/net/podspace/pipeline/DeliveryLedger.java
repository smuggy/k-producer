package net.podspace.pipeline;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

/**
 * Reconciles what was published against what came back, so the tool can answer "did we lose
 * anything?" rather than only "how fast was what arrived?".
 *
 * <p>Every message carries a run id and a monotonic sequence number, and this class issues those
 * sequences - so the produced count is exact rather than inferred. The receive side reports each
 * sequence it sees, and gaps, duplicates and reordering fall out of the comparison.
 *
 * <p><b>Why sequences rather than ids.</b> Tracking a set of outstanding UUIDs costs memory
 * proportional to everything ever sent. Sequences let a fixed-size sliding window do the same job:
 * a {@link BitSet} covering {@value #WINDOW} sequences, about 12KB, regardless of run length.
 *
 * <p><b>Why a window rather than instant judgement.</b> A gap is not loss - the message may still
 * be in flight. A sequence is only declared missing once the window slides past it, which needs
 * {@value #WINDOW} later sequences to arrive. {@link #finalizeOutstanding()} forces the judgement
 * early, for use once the publisher has stopped and the pipeline has drained.
 *
 * <p><b>Run id.</b> Messages from an earlier run are still on the topic and would otherwise read
 * as wild reordering. Anything whose run id is not ours is counted separately and ignored.
 *
 * <p>Counting needs no shared clock, so unlike the latency measurement this stays meaningful with
 * producer and consumer on different machines - though a consumer group spread across instances
 * would break gap detection, since each member sees only its own partitions.
 */
public class DeliveryLedger {
    private static final Logger logger = LoggerFactory.getLogger(DeliveryLedger.class);
    /** Sequences tracked before the oldest is settled. 100k bits is ~12KB. */
    static final int WINDOW = 100_000;

    private final String runId = UUID.randomUUID().toString();
    private final AtomicLong produced = new AtomicLong();

    // Window state. Guarded by `lock`: sequences are issued on the publisher thread, reported on
    // the watcher thread, and read by request threads.
    private final Object lock = new Object();
    private BitSet seen = new BitSet(WINDOW);
    /** Sequence represented by bit 0 of `seen`; everything below this has been settled. */
    private long windowStart = 0;
    private long highestSeen = -1;
    private long received;
    private long duplicates;
    private long outOfOrder;
    private long missing;
    private long late;
    private long foreign;

    public DeliveryLedger(MeterRegistry registry) {
        // These only ever increase, so they are counters rather than gauges - Prometheus needs
        // that to make rate() meaningful and to suffix them _total.
        counter(registry, "kproducer.delivery.produced",
                "Sequences issued, i.e. messages handed to the writer", l -> l.produced.get());
        counter(registry, "kproducer.delivery.received",
                "Distinct messages of this run received", l -> l.received);
        counter(registry, "kproducer.delivery.duplicates",
                "Messages received more than once", l -> l.duplicates);
        counter(registry, "kproducer.delivery.missing",
                "Sequences settled without ever arriving - this is the loss figure", l -> l.missing);
        counter(registry, "kproducer.delivery.late",
                "Messages that arrived after their sequence had already been settled", l -> l.late);
        counter(registry, "kproducer.delivery.outoforder",
                "Messages received below the high-water sequence", l -> l.outOfOrder);
        counter(registry, "kproducer.delivery.foreign",
                "Messages from a different run id, ignored", l -> l.foreign);
        // Can fall as messages arrive, so a gauge.
        Gauge.builder("kproducer.delivery.pending", this, l -> l.readLocked(DeliveryLedger::pending))
                .description("Issued sequences neither received nor yet settled - still in flight")
                .register(registry);
        logger.info("Delivery ledger run id {}", runId);
    }

    private void counter(MeterRegistry registry, String name, String description,
                         ToDoubleFunction<DeliveryLedger> value) {
        FunctionCounter.builder(name, this, l -> l.readLocked(value))
                .description(description)
                .register(registry);
    }

    private double readLocked(ToDoubleFunction<DeliveryLedger> value) {
        synchronized (lock) {
            return value.applyAsDouble(this);
        }
    }

    public String getRunId() {
        return runId;
    }

    /** Issues the next sequence. Calling this <em>is</em> the record that a message was produced. */
    public long nextSequence() {
        return produced.getAndIncrement();
    }

    /** Records a message coming back. Anything belonging to a different run is ignored. */
    public void received(String messageRunId, long seq) {
        synchronized (lock) {
            if (!runId.equals(messageRunId)) {
                foreign++;
                return;
            }
            if (seq < windowStart) {
                // Already settled - it was counted missing when the window slid past it.
                late++;
                return;
            }
            slideTo(seq);
            int bit = (int) (seq - windowStart);
            if (seen.get(bit)) {
                duplicates++;
            } else {
                seen.set(bit);
                received++;
            }
            if (seq < highestSeen) {
                outOfOrder++;
            } else {
                highestSeen = seq;
            }
        }
    }

    /** Grows the window past `seq`, settling anything that drops off the back as missing. */
    private void slideTo(long seq) {
        long overshoot = seq - windowStart - WINDOW + 1;
        if (overshoot <= 0) {
            return;
        }
        int shift = (int) Math.min(overshoot, WINDOW);
        long issued = produced.get();
        for (int i = 0; i < shift; i++) {
            // Only sequences we actually issued can be missing.
            if (!seen.get(i) && (windowStart + i) < issued) {
                missing++;
            }
        }
        seen = seen.get(shift, WINDOW);
        windowStart += shift;
    }

    /**
     * Settles every issued sequence still inside the window, turning "pending" into a verdict.
     * Call once the publisher has stopped and the pipeline has had time to drain.
     */
    public void finalizeOutstanding() {
        synchronized (lock) {
            long issued = produced.get();
            long limit = Math.min(issued, windowStart + WINDOW);
            for (long s = windowStart; s < limit; s++) {
                if (!seen.get((int) (s - windowStart))) {
                    missing++;
                }
            }
            seen = new BitSet(WINDOW);
            windowStart = Math.max(windowStart, issued);
        }
    }

    /** Issued sequences neither received nor yet settled - still legitimately in flight. */
    private double pending() {
        long inWindow = Math.max(0, produced.get() - windowStart);
        return Math.max(0, inWindow - seen.cardinality());
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(runId, produced.get(), received, duplicates, missing, late,
                    outOfOrder, foreign, (long) pending());
        }
    }

    public record Snapshot(String runId, long produced, long received, long duplicates,
                           long missing, long late, long outOfOrder, long foreign, long pending) {
    }
}
