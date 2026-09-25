package net.podspace.domain.codec;

import net.podspace.domain.Temperature;

import java.util.Optional;

/**
 * Encodes and decodes the message payload.
 *
 * <p>Separated from the generator and the consumer because format is orthogonal to both: the same
 * reading is measured identically whether it travels as JSON or Avro, and a run should be able to
 * switch without changing what is being tested.
 *
 * <p>Decoding is split into {@link #claims} and {@link #decode} so a consumer can hold several
 * codecs and route by inspecting the payload rather than being configured to expect one. That
 * matters during a format migration, when a topic carries both at once and a
 * configured-for-one-format consumer would silently discard everything else.
 */
public interface TemperatureCodec {

    /** Short name used in configuration and logs, e.g. {@code json} or {@code avro}. */
    String name();

    byte[] encode(Temperature temperature);

    /**
     * Whether this codec recognizes the payload as its own. Must be cheap - it is called for every
     * message - and must not throw on arbitrary input.
     */
    boolean claims(byte[] payload);

    /** Decodes, or empty if the payload is malformed. Only called when {@link #claims} was true. */
    Optional<Temperature> decode(byte[] payload);
}
