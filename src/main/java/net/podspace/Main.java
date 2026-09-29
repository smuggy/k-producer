package net.podspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main {

    /**
     * Property that reduces a verification run to a single line of output.
     *
     * <p>Read here rather than through the Environment because logging is configured before any
     * bean exists: by the time a @Value could see it, Spring and the Kafka client have already
     * written several hundred lines. Suppressing it afterward is too late.
     */
    private static final String QUIET_PROPERTY = "myapp.verify.quiet";

    static void main(String[] args) {
        if (isQuietRequested(args)) {
            // OFF rather than ERROR: a failing run is meant to be re-run without --quiet to see
            // why. Leaving ERROR on would emit one line per failed iteration during an outage,
            // which is exactly the noise this exists to remove, and the summary already carries
            // the verdict and the counts needed to decide whether to investigate.
            System.setProperty("logging.level.root", "OFF");
            // Three separate channels write to stdout before any of them is the root logger, and
            // silencing only the root leaves roughly twenty lines of banner and log4j2 start-up
            // chatter wrapped around the one line that matters:
            //   - the Spring banner is not logging at all, it is printed directly
            //   - log4j2's StatusLogger reports its own configuration lifecycle
            System.setProperty("spring.main.banner-mode", "off");
            // log4j2.xml reads this for its Configuration status attribute. The generic
            // log4j2.StatusLogger.level below is NOT sufficient on its own: an explicit status
            // attribute in the configuration file overrides it, which is why the status lines
            // survived until this was added.
            System.setProperty("log4j2.statusLevel", "OFF");
            System.setProperty("log4j2.StatusLogger.level", "OFF");
        }
        SpringApplication.run(Main.class, args);
    }

    /**
     * True when quiet mode is requested, from a command-line argument, an environment variable or
     * a system property. Spring's relaxed binding is not available this early, so the environment
     * variable spelling is matched explicitly.
     */
    private static boolean isQuietRequested(String[] args) {
        for (String arg : args) {
            if (arg.equals("--" + QUIET_PROPERTY) || arg.equals("--" + QUIET_PROPERTY + "=true")) {
                return true;
            }
        }
        return "true".equalsIgnoreCase(System.getenv("MYAPP_VERIFY_QUIET"))
                || "true".equalsIgnoreCase(System.getProperty(QUIET_PROPERTY));
    }
}
