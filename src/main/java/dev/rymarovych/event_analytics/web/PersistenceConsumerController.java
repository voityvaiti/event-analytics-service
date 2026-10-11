package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.consumer.PersistenceConsumer;
import dev.rymarovych.event_analytics.consumer.PersistenceConsumerStatus;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service endpoints for the perf harness to stop and restart the persistence consumer. Absent
 * unless enabled; SecurityConfig requires the {@code persistence:control} scope.
 */
@RestController
@RequestMapping("/internal/persistence-consumer")
@ConditionalOnBooleanProperty("app.persistence-consumer.control.enabled")
@Hidden
class PersistenceConsumerController {

  private final PersistenceConsumer consumer;

  PersistenceConsumerController(PersistenceConsumer consumer) {
    this.consumer = consumer;
  }

  @GetMapping
  StatusResponse status() {
    return new StatusResponse(consumer.status());
  }

  @PostMapping("/stop")
  StatusResponse stop() {
    return new StatusResponse(consumer.stop());
  }

  @PostMapping("/start")
  StatusResponse start() {
    return new StatusResponse(consumer.start());
  }

  record StatusResponse(PersistenceConsumerStatus status) {}
}
