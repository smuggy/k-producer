package net.podspace.messaging.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * An idle topic and an unreachable cluster look identical from poll(): both return an empty batch
 * and throw nothing. The partition assignment does not separate them either - it is only cleared
 * by a rebalance callback, and a client that has lost every broker never learns it lost its
 * partitions, so the flag stays stale at true through a total outage.
 *
 * <p>These cover the active check that closes that gap, and equally important, that it stays quiet
 * when the cluster is merely quiet.
 */
class KafkaReaderReachabilityTest {

    private static final String TOPIC = "t";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    /** Short enough that the check fires on every poll, without meaning "disabled". */
    private static final Duration TINY = Duration.ofNanos(1);

    /** A consumer whose endOffsets fails the way an unreachable cluster does. */
    private static class UnreachableAfterAssignment extends MockConsumer<String, String> {
        int endOffsetCalls;
        boolean reachable = true;

        // "earliest": assignment then resolves against the beginning offsets below, rather than
        // seeking to the end and going through the very endOffsets call this test overrides.
        UnreachableAfterAssignment() { super("earliest"); }

        @Override
        public synchronized Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> parts,
                                                                 Duration timeout) {
            endOffsetCalls++;
            if (!reachable) {
                throw new TimeoutException("brokers unreachable");
            }
            return super.endOffsets(parts, timeout);
        }
    }

    private static UnreachableAfterAssignment assignedConsumer() {
        UnreachableAfterAssignment c = new UnreachableAfterAssignment();
        c.updateBeginningOffsets(Map.of(PARTITION, 0L));
        // Needed by the reachable path, where the check delegates to the real endOffsets.
        c.updateEndOffsets(Map.of(PARTITION, 0L));
        return c;
    }

    @Test
    void anIdleButReachableClusterIsNotReportedAsAFailure() {
        UnreachableAfterAssignment consumer = assignedConsumer();
        // One nanosecond, so the check runs on every empty poll rather than after thirty seconds.
        // Not zero: zero means disabled, which is what the configuration property exposes.
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(), TINY);
        consumer.rebalance(List.of(PARTITION));

        for (int i = 0; i < 3; i++) {
            Assertions.assertTrue(reader.readMessage().isEmpty());
        }
        Assertions.assertTrue(consumer.endOffsetCalls > 0, "the check should actually have run");
        // No exception: a quiet topic must not be mistaken for a broken one.
    }

    @Test
    void anUnreachableClusterSurfacesAsAFailureRatherThanSilence() {
        UnreachableAfterAssignment consumer = assignedConsumer();
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(), TINY);
        consumer.rebalance(List.of(PARTITION));

        Assertions.assertTrue(reader.readMessage().isEmpty(), "reachable: quiet, no error");

        // The brokers go away. The assignment flag is deliberately left alone, exactly as a real
        // client behaves when it can no longer reach anyone to be told otherwise.
        consumer.reachable = false;
        Assertions.assertTrue(reader.isReady(),
                "the cached assignment stays stale - which is why loop health alone is not enough");

        Assertions.assertThrows(TimeoutException.class, reader::readMessage,
                "an unreachable cluster must reach WorkerLoop as a failure, not read as idle");
    }

    @Test
    void theCheckIsSkippedWhileNoPartitionsAreAssigned() {
        UnreachableAfterAssignment consumer = assignedConsumer();
        consumer.reachable = false;
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(), TINY);
        // No rebalance, so nothing is assigned.

        Assertions.assertFalse(reader.isReady());
        Assertions.assertDoesNotThrow(reader::readMessage,
                "with no assignment there is nothing to ask about, and isReady already reports it");
        Assertions.assertEquals(0, consumer.endOffsetCalls);
    }

    @Test
    void zeroDisablesTheCheckEntirely() {
        // myapp.kafka.reachabilityCheckSeconds=0 for anyone who would rather rely on commitSync,
        // which usually detects an outage first. Disabled must mean silent, not "check always".
        UnreachableAfterAssignment consumer = assignedConsumer();
        consumer.reachable = false;
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(), Duration.ZERO);
        consumer.rebalance(List.of(PARTITION));

        for (int i = 0; i < 3; i++) {
            Assertions.assertDoesNotThrow(reader::readMessage,
                    "a disabled check must not reach the broker at all");
        }
        Assertions.assertEquals(0, consumer.endOffsetCalls);
    }

    @Test
    void theCheckRunsEvenWhenCommitSyncWouldThrow() {
        // The regression this ordering exists for. commitSync also fails during an outage, and
        // when it ran first it threw before the reachability check could execute - so the check
        // was dead code in precisely the scenario it was written for.
        UnreachableAfterAssignment consumer = new UnreachableAfterAssignment() {
            @Override
            public synchronized void commitSync(java.time.Duration timeout) {
                throw new org.apache.kafka.common.errors.TimeoutException("coordinator gone");
            }
        };
        consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
        consumer.updateEndOffsets(Map.of(PARTITION, 0L));
        consumer.reachable = false;
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(), TINY);
        consumer.rebalance(List.of(PARTITION));

        Assertions.assertThrows(TimeoutException.class, reader::readMessage);
        Assertions.assertTrue(consumer.endOffsetCalls > 0,
                "the reachability check must run before commitSync, or it never runs at all");
    }

    @Test
    void theCheckIsRateLimitedRatherThanRunOnEveryEmptyPoll() {
        UnreachableAfterAssignment consumer = assignedConsumer();
        KafkaReader reader = new KafkaReader(consumer, TOPIC, new SimpleMeterRegistry(),
                Duration.ofMinutes(10));
        consumer.rebalance(List.of(PARTITION));

        for (int i = 0; i < 5; i++) {
            reader.readMessage();
        }
        Assertions.assertEquals(0, consumer.endOffsetCalls,
                "a busy or briefly idle reader must not pay for the check on every poll");
    }
}
