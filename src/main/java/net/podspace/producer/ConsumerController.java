package net.podspace.producer;

import net.podspace.consumer.ValueEnvelope;
import net.podspace.consumer.Watcher;
import net.podspace.domain.Temperature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@RestController
@RequestMapping("/consumer")
public class ConsumerController {
    private static class ItemStat {
        String id;
        double timeDifference;
        int size;
    }
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final Watcher<Temperature> watcher;
    private final BlockingQueue<ValueEnvelope<Temperature>> items;
    private final List<ItemStat> list;

    public ConsumerController(Watcher<Temperature> watcher) {
        this.list    = new ArrayList<>();
        this.watcher = watcher;
        this.items   = new ArrayBlockingQueue<>(2_000);
        this.watcher.setReturnQueue(items);
    }

    @GetMapping("/start")
    public String startConsumer() {
        logger.info("Calling watcher initiate.");
        watcher.initiate();
        logger.info("Woot... started.");
        return "Success... started";
    }

    @GetMapping("/stop")
    public String stopWatcher() {
        try {
            logger.info("Calling watcher quit.");
            watcher.quit();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... stopped.");
        return "Success... stopped";
    }

    @GetMapping("/pause")
    public String pauseWatcher() {
        try {
            logger.info("Calling watcher pause.");
            watcher.pause();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... paused.");
        return "Success... paused";
    }

    @GetMapping("/resume")
    public String resumeWatcher() {
        try {
            logger.info("Calling watcher resume.");
            watcher.resume();
        } catch (Throwable t) {
            logger.warn("Error occurred.", t);
            return "Failure... I dunno";
        }
        logger.info("Woot... resumed.");
        return "Success... resumed";
    }

    @GetMapping("/stats")
    public String statistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>time id</th><th>time difference</th><th>size</th></tr>");

        while (!items.isEmpty()) {
            var i = items.poll();
            var t = i.item;
            LocalDateTime readTime = LocalDateTime.parse(i.time, formatter);
            LocalDateTime writeTime = LocalDateTime.parse(t.getTime(), formatter);
            Duration d = Duration.between(writeTime, readTime);
            ItemStat itemStat = new ItemStat();
            itemStat.id = t.getTimeId();
            itemStat.timeDifference = d.toSeconds() + ((double)d.getNano())/1_000_000_000.0;
            itemStat.size = i.size;
            list.add(itemStat);
        }

        for (ItemStat t : list) {
            String message = "Id: " + t.id + " seconds: " + t.timeDifference;
            String htmlMess = "<tr><td>" + t.id + "</td><td>" + String.format("%.6f", t.timeDifference) + "</td><td>" +
                    t.size + "</td></tr>";
            sb.append(htmlMess).append("\n");
            logger.debug(message);
        }

        sb.append("</table><p>total messages: ").append(list.size());
        return sb.toString();
    }
}
