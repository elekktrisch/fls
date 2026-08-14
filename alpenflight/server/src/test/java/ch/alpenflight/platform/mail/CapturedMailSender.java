package ch.alpenflight.platform.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

public class CapturedMailSender implements MailSender {

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }

    public List<MailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        CapturedMailSender capturedMailSender() {
            return new CapturedMailSender();
        }
    }
}
