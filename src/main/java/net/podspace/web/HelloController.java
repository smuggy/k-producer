package net.podspace.web;

import net.podspace.config.MyBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    private static final Logger logger = LoggerFactory.getLogger(HelloController.class);
    private final MyBean b;

    public HelloController(MyBean b){
        this.b = b;
    }

    @GetMapping("/")
    public String index()
    {
        String version;
        try {
            logger.info("home page called");
            version = System.getenv("APP_VERSION");
            if (version == null || version.isBlank()) {
                version = "None";
            }
        } catch (SecurityException ignore) {
            version = "None";
        }

        return "Greetings, application version is " + version;
    }

    @GetMapping("/other")
    public String other() {
        return "Value = " + b.getValue();
    }
}
