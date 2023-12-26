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
}
