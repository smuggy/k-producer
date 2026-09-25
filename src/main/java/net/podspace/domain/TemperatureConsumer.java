package net.podspace.domain;

import net.podspace.domain.codec.JsonTemperatureCodec;
import net.podspace.domain.codec.TemperatureCodec;
import net.podspace.messaging.MessageConsumer;
import net.podspace.messaging.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Decodes whatever arrives, rather than what it was told to expect.
 *
 * <p>Format is detected per message instead of configured, because the producer's choice and the
 * consumer's are not necessarily the same at the same moment. During a format migration a topic
 * carries both, and a consumer pinned to one would discard every message in the other while
 * reporting itself perfectly healthy - the reconciliation figures would show total loss against a
 * cluster that delivered everything. The two encodings are unambiguous: Confluent-framed Avro
 * begins with a 0x00 magic byte, JSON with '{'.
 */
public class TemperatureConsumer implements MessageConsumer<Temperature> {
    private static final Logger logger = LoggerFactory.getLogger(TemperatureConsumer.class);

    private final List<TemperatureCodec> codecs;

    /** JSON only - the default when no registry is configured. */
    public TemperatureConsumer() {
        this(List.of(new JsonTemperatureCodec()));
    }

    public TemperatureConsumer(List<TemperatureCodec> codecs) {
        this.codecs = List.copyOf(codecs);
    }

    @Override
    public Optional<Pair<Temperature, Integer>> getMessage(byte[] message) {
        if (message == null || message.length == 0) {
            return Optional.empty();
        }
        for (TemperatureCodec codec : codecs) {
            if (!codec.claims(message)) {
                continue;
            }
            // Size is the encoded length, which is what actually crossed the wire - and the figure
            // worth comparing when the same reading is sent in two formats.
            return codec.decode(message)
                    .map(t -> new Pair<>(t, message.length));
        }
        logger.debug("No codec recognised a {}-byte payload starting 0x{}",
                message.length, String.format("%02x", message[0]));
        return Optional.empty();
    }
}
