package dev.rymarovych.event_analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EventAnalyticsApplication {

  public static void main(String[] args) {
    SpringApplication.run(EventAnalyticsApplication.class, args);
  }
}
