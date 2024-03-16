package net.podspace.producer;

import net.podspace.producer.generator.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/generator")
public class GeneratorController {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private final Generator generator;

    public GeneratorController(Generator generator) {
        this.generator = generator;
    }
    @GetMapping("/start")
    public String startGenerator() {
        logger.info("Calling generator initiate.");
        generator.initiate();
        logger.info("Woot... started.");
        return "Success... started";
    }

    @GetMapping("/stop")
    public String stopGenerator() {
        try {
            logger.info("Calling generator quit.");
            generator.quit();
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
            generator.pause();
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
            generator.resume();
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
            var seconds = generator.getSeconds();
            if (seconds > 1)
                generator.setSeconds(seconds - 1);
            else
                generator.setSeconds(1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to " + generator.getSeconds() + " seconds.");
        return "Success... lowered time";
    }
    @GetMapping("/raisesleep")
    public String raiseSleep() {
        try {
            logger.info("Calling generator quit.");
            var seconds = generator.getSeconds();
            generator.setSeconds(seconds + 1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to " + generator.getSeconds() + " seconds.");
        return "Success... raised time";
    }
    @GetMapping("/lowermessages")
    public String lowerMessages() {
        try {
            logger.info("Calling generator quit.");
            var messages = generator.getMessages();
            if (messages <= 5) generator.setMessages(1);
            else generator.setMessages(messages - 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to " + generator.getMessages() + " messages.");
        return "Success... lowered messages";
    }
    @GetMapping("/raisemessages")
    public String raiseMessages() {
        try {
            logger.info("Calling generator quit.");
            var messages = generator.getMessages();
            generator.setMessages(messages + 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to " + generator.getMessages() + " messages.");
        return "Success... raised messages";
    }
    @GetMapping("/lowerfillersize")
    public String lowerFillerSize() {
        try {
            logger.info("Calling generator quit.");
            var fillerSize = generator.getFillerSize();
            generator.setFillerSize(fillerSize - 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered filler to " + generator.getFillerSize() + " bytes.");
        return "Success... lowered filler size";
    }
    @GetMapping("/raisefillersize")
    public String raiseFillerSize() {
        try {
            logger.info("Calling generator quit.");
            var fillerSize = generator.getFillerSize();
            generator.setFillerSize(fillerSize + 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised filler to " + generator.getFillerSize() + " bytes.");
        return "Success... raised filler size";
    }
}
