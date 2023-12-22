package net.podspace.producer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
    private final MyBean b;

    public HelloController(MyBean b) {
        this.b = b;
    }

    @GetMapping("/")
    public String index() {
        return "Greetings";
    }

    @GetMapping("/other")
    public String other() {
        return "Value = " + b.getValue();
    }
}
