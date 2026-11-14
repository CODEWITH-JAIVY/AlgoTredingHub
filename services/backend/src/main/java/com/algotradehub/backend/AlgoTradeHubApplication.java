package com.algotradehub.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlgoTradeHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlgoTradeHubApplication.class, args);
    }
}
