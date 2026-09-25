package net.podspace.domain.codec;

import net.podspace.domain.Temperature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The original payload format: UTF-8 JSON, readable straight off the topic with
 * {@code kafka-console-consumer} and needing no registry. It stays the default for that reason -
 * the probe has to run against a cluster with no schema infrastructure at all.
 */
public class JsonTemperatureCodec implements TemperatureCodec {
    private static final Logger logger = LoggerFactory.getLogger(JsonTemperatureCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "json";
    }

    @Override
    public byte[] encode(Temperature temperature) {
        return temperature.toJsonString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean claims(byte[] payload) {
        // A JSON object starts with '{', possibly after whitespace. Deliberately not "anything
        // that is not Avro": an unrecognized payload should fall through as undecodable rather
        // than be handed to a parser that will fail noisily on every message.
        for (byte b : payload) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{';
        }
        return false;
    }

    @Override
    public Optional<Temperature> decode(byte[] payload) {
        try {
            return Optional.ofNullable(
                    MAPPER.readValue(new String(payload, StandardCharsets.UTF_8), Temperature.class));
        } catch (JacksonException e) {
            logger.warn("Unable to map JSON payload to a Temperature.", e);
            return Optional.empty();
        }
    }
}
