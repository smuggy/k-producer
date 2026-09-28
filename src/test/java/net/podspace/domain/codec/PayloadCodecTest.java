package net.podspace.domain.codec;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import net.podspace.domain.TempScale;
import net.podspace.domain.Temperature;
import net.podspace.domain.TemperatureConsumer;
import net.podspace.messaging.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The payload format must be interchangeable without changing what is measured, and the consumer
 * has to cope with a topic carrying both at once.
 */
class PayloadCodecTest {

    private static final String TOPIC = "test-topic-one";

    private static AvroTemperatureCodec avro() {
        SchemaRegistryClient registry = new MockSchemaRegistryClient();
        return new AvroTemperatureCodec(registry, TOPIC, Map.of("schema.registry.url", "mock://test"));
    }

    private static Temperature sample() {
        return Temperature.createCelsiusTemp(21.5, "padding", "run-abc", 42L);
    }

    // --- round trips ----------------------------------------------------------------------

    @Test
    void avroRoundTripPreservesEveryFieldThatMatters() {
        Temperature original = sample();
        AvroTemperatureCodec codec = avro();

        Temperature back = codec.decode(codec.encode(original)).orElseThrow();

        // The timestamp is the whole measurement: latency is this value subtracted from the
        // consume-side clock, so an encoding that rounds or reformats it silently corrupts every
        // sample. Asserted first because it is the field with the most to lose.
        Assertions.assertEquals(original.getTime(), back.getTime(), "timestamp must survive exactly");
        Assertions.assertEquals(original.getRun(), back.getRun(), "run id drives reconciliation");
        Assertions.assertEquals(original.getSeq(), back.getSeq(), "sequence drives reconciliation");
        Assertions.assertEquals(original.getTempId(), back.getTempId());
        Assertions.assertEquals(original.getTemp(), back.getTemp(), 1e-9);
        Assertions.assertEquals(original.getScale(), back.getScale());
        Assertions.assertEquals(original.getFiller(), back.getFiller());
    }

    @Test
    void jsonRoundTripPreservesEveryFieldThatMatters() {
        Temperature original = sample();
        JsonTemperatureCodec codec = new JsonTemperatureCodec();

        Temperature back = codec.decode(codec.encode(original)).orElseThrow();

        Assertions.assertEquals(original.getTime(), back.getTime());
        Assertions.assertEquals(original.getRun(), back.getRun());
        Assertions.assertEquals(original.getSeq(), back.getSeq());
        Assertions.assertEquals(original.getScale(), back.getScale());
    }

    @Test
    void bothFormatsCarryTheSameReading() {
        Temperature original = sample();
        Temperature viaJson = new JsonTemperatureCodec()
                .decode(new JsonTemperatureCodec().encode(original)).orElseThrow();
        AvroTemperatureCodec a = avro();
        Temperature viaAvro = a.decode(a.encode(original)).orElseThrow();

        Assertions.assertEquals(viaJson.getTime(), viaAvro.getTime());
        Assertions.assertEquals(viaJson.getRun(), viaAvro.getRun());
        Assertions.assertEquals(viaJson.getSeq(), viaAvro.getSeq());
        Assertions.assertEquals(viaJson.getTemp(), viaAvro.getTemp(), 1e-9);
    }

    // --- format detection -----------------------------------------------------------------

    @Test
    void theTwoFormatsAreDistinguishableOnTheWire() {
        AvroTemperatureCodec a = avro();
        JsonTemperatureCodec j = new JsonTemperatureCodec();
        byte[] avroBytes = a.encode(sample());
        byte[] jsonBytes = j.encode(sample());

        Assertions.assertEquals(AvroTemperatureCodec.MAGIC_BYTE, avroBytes[0],
                "Confluent framing starts with a 0x00 magic byte");
        Assertions.assertEquals((byte) '{', jsonBytes[0]);

        Assertions.assertTrue(a.claims(avroBytes));
        Assertions.assertFalse(a.claims(jsonBytes), "avro must not claim a JSON payload");
        Assertions.assertTrue(j.claims(jsonBytes));
        Assertions.assertFalse(j.claims(avroBytes), "json must not claim an avro payload");
    }

    @Test
    void neitherCodecClaimsArbitraryInput() {
        AvroTemperatureCodec a = avro();
        JsonTemperatureCodec j = new JsonTemperatureCodec();
        // Must not throw, and must not claim - an unrecognised payload should fall through as
        // undecodable rather than be handed to a parser that fails on every message.
        for (byte[] junk : List.of(new byte[0], new byte[]{1}, "not json".getBytes(StandardCharsets.UTF_8),
                new byte[]{0x00, 0x01})) {
            Assertions.assertFalse(a.claims(junk), "avro claimed junk");
            Assertions.assertFalse(j.claims(junk), "json claimed junk");
        }
    }

    @Test
    void jsonIsStillClaimedWithLeadingWhitespace() {
        Assertions.assertTrue(new JsonTemperatureCodec()
                .claims("  \n {\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
    }

    // --- the consumer routing, which is the point of the whole design ----------------------

    @Test
    void aConsumerWithBothCodecsReadsAMixedFormatTopic() {
        // The migration case. A consumer pinned to one format would discard every message in the
        // other while reporting itself healthy, and the reconciliation figures would show total
        // loss against a cluster that delivered everything.
        AvroTemperatureCodec a = avro();
        JsonTemperatureCodec j = new JsonTemperatureCodec();
        TemperatureConsumer consumer = new TemperatureConsumer(List.of(j, a));

        Temperature fromJson = consumer.getMessage(j.encode(sample())).orElseThrow().a();
        Temperature fromAvro = consumer.getMessage(a.encode(sample())).orElseThrow().a();

        Assertions.assertEquals("run-abc", fromJson.getRun());
        Assertions.assertEquals("run-abc", fromAvro.getRun());
        Assertions.assertEquals(42L, fromJson.getSeq());
        Assertions.assertEquals(42L, fromAvro.getSeq());
    }

    @Test
    void aJsonOnlyConsumerSkipsAvroRatherThanMisreadingIt() {
        // Without a registry configured the consumer has only the JSON codec. Avro must come back
        // empty - counted as skipped - not as a garbled Temperature that would pollute latency.
        TemperatureConsumer jsonOnly = new TemperatureConsumer();
        Assertions.assertEquals(Optional.empty(), jsonOnly.getMessage(avro().encode(sample())));
    }

    @Test
    void reportedSizeIsTheEncodedLength() {
        AvroTemperatureCodec a = avro();
        byte[] encoded = a.encode(sample());
        Pair<Temperature, Integer> p =
                new TemperatureConsumer(List.of(a)).getMessage(encoded).orElseThrow();
        Assertions.assertEquals(encoded.length, p.b(),
                "size must be what crossed the wire, so formats can be compared");
    }

    @Test
    void anEmptyOrNullPayloadIsSkipped() {
        TemperatureConsumer c = new TemperatureConsumer();
        Assertions.assertEquals(Optional.empty(), c.getMessage(null));
        Assertions.assertEquals(Optional.empty(), c.getMessage(new byte[0]));
    }

    @Test
    void avroIsMoreCompactThanJsonForTheSameReading() {
        // Not a requirement, but a claim worth holding to: if Avro ever became larger than JSON
        // for an identical reading, the schema has probably drifted into carrying something it
        // should not.
        Temperature t = Temperature.createCelsiusTemp(21.5, "", "run-abc", 42L);
        int avroSize = avro().encode(t).length;
        int jsonSize = new JsonTemperatureCodec().encode(t).length;
        Assertions.assertTrue(avroSize < jsonSize,
                "avro " + avroSize + " should be smaller than json " + jsonSize);
    }

    @Test
    void scaleSymbolsSurviveBothDirections() {
        Assertions.assertEquals(TempScale.CELSIUS, TempScale.fromSymbol("C"));
        Assertions.assertEquals(TempScale.FAHRENHEIT, TempScale.fromSymbol("F"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TempScale.fromSymbol("K"));
    }
}
