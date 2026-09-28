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
        return notRunningNote() + "Success... lowered time";
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
        return notRunningNote() + "Success... raised time";
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
        return notRunningNote() + "Success... lowered messages";
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
        return notRunningNote() + "Success... raised messages";
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
        return notRunningNote() + "Success... lowered filler size";
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
        return notRunningNote() + "Success... raised filler size";
    }

    /**
     * Prefix warning a caller that an adjustment has nowhere to take effect yet.
     *
     * <p>The mutators succeed whether or not the publisher is running - the value is stored either
     * way - so a stopped engine otherwise reports "Success" while nothing changes. That is
     * indistinguishable from a working adjustment, and it is exactly what happens when these are
     * called on an echo instance, which never starts a publisher at all.
     */
    private String notRunningNote() {
        return publisher.isRunning() ? "" : "(publisher is not running) ";
    }

    @GetMapping("/settings")
    public String settings() {
        boolean running = publisher.isRunning();
        // Running state first, because without it these numbers are misleading: the echo role
        // builds a publisher and exposes this whole endpoint but never starts it, so the settings
        // below describe an engine that is not publishing anything.
        return "<html><head><title>Publisher Settings</title></head><body>" +
                "<table><tr><th>key</th><th>value</th></tr>" +
                "<tr><td>Running</td><td>" + (running ? "yes" : "no") + "</td></tr>" +
                "<tr><td>Sleep time (half seconds)</td><td>" + publisher.getSleep() + "</td></tr>" +
                "<tr><td>Messages</td><td>" + publisher.getMessages() + "</td></tr>" +
                "<tr><td>Filler size</td><td>" + publisher.getFillerSize() + "</td></tr>" +
                "</table>" +
                (running ? "" : "<p><b>The publisher is not running.</b> These settings are stored "
                        + "and will apply once it starts, but nothing is being published. Call "
                        + "/publisher/start, or check myapp.role - the echo role relays only and "
                        + "never starts a publisher.</p>") +
                "</body></html>";
    }
}
