package net.podspace.producer;

import net.podspace.producer.generator.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher")
public class PublisherController {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
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

    @GetMapping("/lowersleep")
    public String lowerSleep() {
        try {
            logger.info("Calling publisher lower sleep.");
            var time = publisher.getSleep();
            publisher.setSleep(time - 1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to {} half seconds.", publisher.getSleep());
        return "Success... lowered time";
    }

    @GetMapping("/raisesleep")
    public String raiseSleep() {
        try {
            logger.info("Calling publisher raise sleep.");
            var time = publisher.getSleep();
            publisher.setSleep(time + 1);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to {} half seconds.", publisher.getSleep());
        return "Success... raised time";
    }

    @GetMapping("/lowermessages")
    public String lowerMessages() {
        try {
            logger.info("Calling publisher lower messages.");
            var messages = publisher.getMessages();
            if (messages <= 5) publisher.setMessages(1);
            else publisher.setMessages(messages - 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered to {} messages.", publisher.getMessages());
        return "Success... lowered messages";
    }

    @GetMapping("/raisemessages")
    public String raiseMessages() {
        try {
            logger.info("Calling publisher raise messages.");
            var messages = publisher.getMessages();
            publisher.setMessages(messages + 5);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised to {} messages.", publisher.getMessages());
        return "Success... raised messages";
    }

    @GetMapping("/lowerfillersize")
    public String lowerFillerSize() {
        try {
            logger.info("Calling publisher lower filler size.");
            var fillerSize = publisher.getFillerSize();
            publisher.setFillerSize(fillerSize - 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... lowered filler to {} bytes.", publisher.getFillerSize());
        return "Success... lowered filler size";
    }

    @GetMapping("/raisefillersize")
    public String raiseFillerSize() {
        try {
            logger.info("Calling publisher raise filler size.");
            var fillerSize = publisher.getFillerSize();
            publisher.setFillerSize(fillerSize + 512);
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... raised filler to {} bytes.", publisher.getFillerSize());
        return "Success... raised filler size";
    }

    @GetMapping("/settings")
    public String settings() {
        String sb = "<html><head><title>Publisher Settings</title></head><body>" +
                "<table><tr><th>key</th><th>value</th></tr>" +
                "<tr><td>Sleep time</td><td>" + publisher.getSleep() + "</td></tr>" +
                "<tr><td>Messages</td><td>" + publisher.getMessages() + "</td></tr>" +
                "<tr><td>Filler size</td><td>" + publisher.getFillerSize() + "</td></tr>" +
                "</table></body></html>";
        return sb;
    }
}
