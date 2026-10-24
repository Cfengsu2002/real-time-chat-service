package dev.relay.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class RelayChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayChatApplication.class, args);
    }
}
