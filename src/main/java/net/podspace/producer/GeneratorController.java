package net.podspace.producer;

import net.podspace.producer.generator.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/generator")
public class GeneratorController {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private final Publisher publisher;

    public GeneratorController(Publisher publisher) {
        this.publisher = publisher;
    }
    @GetMapping("/start")
    public String startGenerator() {
        logger.info("Calling generator initiate.");
        publisher.initiate();
        logger.info("Woot... started.");
        return "Success... started";
    }

    @GetMapping("/stop")
    public String stopGenerator() {
        try {
            logger.info("Calling generator quit.");
            publisher.quit();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... stopped.");
        return "Success... stopped";
    }

    @GetMapping("/pause")
    public String pauseGenerator() {
        try {
            logger.info("Calling generator quit.");
            publisher.pause();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... paused.");
        return "Success... paused";
    }

    @GetMapping("/resume")
    public String resumeGenerator() {
        try {
            logger.info("Calling generator quit.");
            publisher.resume();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... resumed.");
        return "Success... resumed";
    }
    @GetMapping("/lowersleep")
    public String lowerSleep() {
        try {
            logger.info("Calling generator quit.");
            var seconds = publisher.getSeconds();
            if (seconds > 1)
                publisher.setSeconds(seconds - 1);
            else
                publisher.setSeconds(1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to " + publisher.getSeconds() + " seconds.");
        return "Success... lowered time";
    }
    @GetMapping("/raisesleep")
    public String raiseSleep() {
        try {
            logger.info("Calling generator quit.");
            var seconds = publisher.getSeconds();
            publisher.setSeconds(seconds + 1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to " + publisher.getSeconds() + " seconds.");
        return "Success... raised time";
    }
    @GetMapping("/lowermessages")
    public String lowerMessages() {
        try {
            logger.info("Calling generator quit.");
            var messages = publisher.getMessages();
            if (messages <= 5) publisher.setMessages(1);
            else publisher.setMessages(messages - 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to " + publisher.getMessages() + " messages.");
        return "Success... lowered messages";
    }
    @GetMapping("/raisemessages")
    public String raiseMessages() {
        try {
            logger.info("Calling generator quit.");
            var messages = publisher.getMessages();
            publisher.setMessages(messages + 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to " + publisher.getMessages() + " messages.");
        return "Success... raised messages";
    }
    @GetMapping("/lowerfillersize")
    public String lowerFillerSize() {
        try {
            logger.info("Calling generator quit.");
            var fillerSize = publisher.getFillerSize();
            publisher.setFillerSize(fillerSize - 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered filler to " + publisher.getFillerSize() + " bytes.");
        return "Success... lowered filler size";
    }
    @GetMapping("/raisefillersize")
    public String raiseFillerSize() {
        try {
            logger.info("Calling generator quit.");
            var fillerSize = publisher.getFillerSize();
            publisher.setFillerSize(fillerSize + 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised filler to " + publisher.getFillerSize() + " bytes.");
        return "Success... raised filler size";
    }
}
