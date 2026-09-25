package net.podspace.domain.codec;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import net.podspace.domain.TempScale;
import net.podspace.domain.Temperature;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

/**
 * Confluent-framed Avro: a 0x00 magic byte, a four-byte schema id, then the Avro binary body.
 *
 * <p>Exercising this is the point of supporting it at all. A schema registry is an availability
 * dependency alongside the brokers - one that is slow, unreachable, or rejecting an incompatible
 * schema is a production failure mode the probe otherwise cannot reproduce. Using the real
 * Confluent framing rather than a hand-rolled encoding is what makes the messages readable by
 * {@code kafka-avro-console-consumer} and by any other consumer on the topic.
 *
 * <p>The schema is loaded from the classpath rather than generated code, so it is the same file
 * {@code scripts/register-schema.sh} publishes and there is one definition to keep in step.
 */
public class AvroTemperatureCodec implements TemperatureCodec {
    private static final Logger logger = LoggerFactory.getLogger(AvroTemperatureCodec.class);
    /** Confluent's wire-format marker. The first byte of every registry-framed payload. */
    static final byte MAGIC_BYTE = 0x0;
    private static final String SCHEMA_RESOURCE = "/avro/temperature.avsc";

    private final Schema schema;
    private final String topic;
    private final KafkaAvroSerializer serializer;
    private final KafkaAvroDeserializer deserializer;

    /**
     * @param topic the topic these messages are written to. Confluent's default subject naming
     *              derives the registry subject from it as {@code <topic>-value}, so it has to
     *              match what the schema was registered under or the serializer will not find it.
     */
    public AvroTemperatureCodec(SchemaRegistryClient registryClient, String topic,
                                Map<String, ?> config) {
        this.schema = loadSchema();
        this.topic = topic;
        this.serializer = new KafkaAvroSerializer(registryClient);
        this.serializer.configure(config, false);
        this.deserializer = new KafkaAvroDeserializer(registryClient);
        this.deserializer.configure(config, false);
        logger.info("Avro codec ready for topic {} using schema {}", topic, schema.getFullName());
    }

    private static Schema loadSchema() {
        try (InputStream in = AvroTemperatureCodec.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("schema not on the classpath: " + SCHEMA_RESOURCE);
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + SCHEMA_RESOURCE, e);
        }
    }

    @Override
    public String name() {
        return "avro";
    }

    @Override
    public byte[] encode(Temperature t) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("id", t.getTempId());
        record.put("temp", t.getTemp());
        record.put("time", t.getTime());
        record.put("scale", new GenericData.EnumSymbol(
                schema.getField("scale").schema(), t.getScale().getScale()));
        record.put("run", t.getRun() == null ? "" : t.getRun());
        record.put("seq", t.getSeq());
        record.put("filler", t.getFiller() == null ? "" : t.getFiller());
        return serializer.serialize(topic, record);
    }

    @Override
    public boolean claims(byte[] payload) {
        // Magic byte plus the four-byte schema id. Anything shorter cannot be a framed payload,
        // and checking the length first keeps this safe on arbitrary input.
        return payload.length > 5 && payload[0] == MAGIC_BYTE;
    }

    @Override
    public Optional<Temperature> decode(byte[] payload) {
        try {
            Object decoded = deserializer.deserialize(topic, payload);
            if (!(decoded instanceof GenericRecord record)) {
                logger.warn("Avro payload decoded to {}, not a record", 
                        decoded == null ? "null" : decoded.getClass());
                return Optional.empty();
            }
            return Optional.of(Temperature.received(
                    text(record, "id"),
                    (Double) record.get("temp"),
                    text(record, "time"),
                    TempScale.fromSymbol(text(record, "scale")),
                    text(record, "run"),
                    (Long) record.get("seq"),
                    text(record, "filler")));
        } catch (RuntimeException e) {
            // Covers an unreachable registry and an unknown schema id alike. Returning empty lets
            // the watcher count it as skipped and carry on, which is the same treatment a
            // malformed JSON message gets - a registry outage must not stop the loop.
            logger.warn("Unable to decode Avro payload.", e);
            return Optional.empty();
        }
    }

    /** Avro hands back Utf8 rather than String for string fields. */
    private static String text(GenericRecord record, String field) {
        Object v = record.get(field);
        return v == null ? null : v.toString();
    }
}
