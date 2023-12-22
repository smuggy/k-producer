package net.podspace.producer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.logging.Logger;

@Configuration
public class AppConfig {
    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());

    @Value("${myapp.val}")
    private String val;

    @Bean
    public MyBean beanInstance() {
        logger.info("==> Creating bean instance with " + val);// + "\t other value is");

        MyBean m = new MyBean();
        m.setValue(val);
        return m;
    }
}
