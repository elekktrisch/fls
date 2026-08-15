package ch.alpenflight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlpenFlightApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlpenFlightApplication.class, args);
    }
}
