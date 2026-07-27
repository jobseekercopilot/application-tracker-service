package com.jobseekercopilot.applicationtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApplicationTrackerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationTrackerServiceApplication.class, args);
    }
}
