package com.cabin.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CabinOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CabinOrchestratorApplication.class, args);
    }
}
