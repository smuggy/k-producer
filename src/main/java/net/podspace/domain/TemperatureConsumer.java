package net.podspace.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.podspace.messaging.MessageConsumer;
import net.podspace.messaging.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class TemperatureConsumer implements MessageConsumer<Temperature> {
    private static final Logger logger = LoggerFactory.getLogger(TemperatureConsumer.class);
    static ObjectMapper mapper = new ObjectMapper();

    public Optional<Pair<Temperature, Integer>> getMessage(String s) {
        try {
            var o = mapper.readValue(s, Temperature.class);
            if (o == null)
                return Optional.empty();
            Pair<Temperature, Integer> p = new Pair<>(o, s.length());
            return Optional.of(p);
        } catch (JacksonException jme) {
            logger.warn("Unable to map string to object.", jme);
            return Optional.empty();
        }
    }
}
