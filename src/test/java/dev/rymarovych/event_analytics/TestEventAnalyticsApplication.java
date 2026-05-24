package dev.rymarovych.event_analytics;

import org.springframework.boot.SpringApplication;

public class TestEventAnalyticsApplication {

  public static void main(String[] args) {
    SpringApplication.from(EventAnalyticsApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
