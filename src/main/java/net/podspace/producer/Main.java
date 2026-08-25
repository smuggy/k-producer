package net.podspace.producer;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Main {
    //private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        //logger.info("Hello logging world!");
        SpringApplication.run(Main.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            System.out.println("Let's inspect beans provided:");
//          String [] beanNames = ctx.getBeanDefinitionNames();
//          for (String beanName: beanNames) {
//                System.out.println(beanName);
//          }
        };
    }
}
