package net.podspace.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.podspace.consumer.MessageConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class TemperatureConsumer implements MessageConsumer<Temperature> {
    private static final Logger logger = LoggerFactory.getLogger(TemperatureConsumer.class);
    static ObjectMapper mapper = new ObjectMapper();
    public Optional<Temperature> getMessage(String s) {
        try {
            var o = mapper.readValue(s, Temperature.class);
            return Optional.of(o);
        } catch (JsonProcessingException jme) {
            logger.warn("Unable to map string to object.", jme);
            return Optional.empty();
        }
    }
}
