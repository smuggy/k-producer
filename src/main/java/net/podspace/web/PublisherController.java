package net.podspace.web;

import net.podspace.pipeline.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher")
public class PublisherController {
    private static final Logger logger = LoggerFactory.getLogger(PublisherController.class);
    private final Publisher publisher;

    public PublisherController(Publisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/start")
    public String startPublisher() {
        logger.info("Calling publisher initiate.");
        publisher.initiate();
        logger.info("Woot... started.");
        return "Success... started";
    }

    @GetMapping("/stop")
    public String stopPublisher() {
        try {
            logger.info("Calling publisher quit.");
            publisher.quit();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... stopped.");
        return "Success... stopped";
    }

    @GetMapping("/pause")
    public String pausePublisher() {
        try {
            logger.info("Calling publisher pause.");
            publisher.pause();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... paused.");
        return "Success... paused";
    }

    @GetMapping("/resume")
    public String resumePublisher() {
        try {
            logger.info("Calling publisher resume.");
            publisher.resume();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... resumed.");
        return "Success... resumed";
    }

    // The mutators below adjust by a delta in one atomic step, and log the value THIS call
    // produced. The previous get-then-set pair could lose an update when two requests overlapped,
    // and the trailing re-read could report a value a concurrent request had already changed.
    @GetMapping("/lowersleep")
    public String lowerSleep() {
        long now;
        try {
            logger.info("Calling publisher lower sleep.");
            now = publisher.adjustSleep(-1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to {} half seconds.", now);
        return "Success... lowered time";
    }

    @GetMapping("/raisesleep")
    public String raiseSleep() {
        long now;
        try {
            logger.info("Calling publisher raise sleep.");
            now = publisher.adjustSleep(1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to {} half seconds.", now);
        return "Success... raised time";
    }

    @GetMapping("/lowermessages")
    public String lowerMessages() {
        long now;
        try {
            logger.info("Calling publisher lower messages.");
            now = publisher.adjustMessages(-5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to {} messages.", now);
        return "Success... lowered messages";
    }

    @GetMapping("/raisemessages")
    public String raiseMessages() {
        long now;
        try {
            logger.info("Calling publisher raise messages.");
            now = publisher.adjustMessages(5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to {} messages.", now);
        return "Success... raised messages";
    }

    @GetMapping("/lowerfillersize")
    public String lowerFillerSize() {
        int now;
        try {
            logger.info("Calling publisher lower filler size.");
            now = publisher.adjustFillerSize(-512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered filler to {} bytes.", now);
        return "Success... lowered filler size";
    }

    @GetMapping("/raisefillersize")
    public String raiseFillerSize() {
        int now;
        try {
            logger.info("Calling publisher raise filler size.");
            now = publisher.adjustFillerSize(512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised filler to {} bytes.", now);
        return "Success... raised filler size";
    }

    @GetMapping("/settings")
    public String settings() {
        return "<html><head><title>Publisher Settings</title></head><body>" +
                "<table><tr><th>key</th><th>value</th></tr>" +
                "<tr><td>Sleep time (half seconds)</td><td>" + publisher.getSleep() + "</td></tr>" +
                "<tr><td>Messages</td><td>" + publisher.getMessages() + "</td></tr>" +
                "<tr><td>Filler size</td><td>" + publisher.getFillerSize() + "</td></tr>" +
                "</table></body></html>";
    }
}
