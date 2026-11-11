package eu.glazelab.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GlazelabApplication {
    public static void main(String[] args) { SpringApplication.run(GlazelabApplication.class, args); }
}
