package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.consumer.PersistenceConsumer;
import dev.rymarovych.event_analytics.consumer.PersistenceConsumerStatus;
import dev.rymarovych.event_analytics.service.DeadLetterRedriveService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets an operator stop, restart and redrive the consumer; off by default, needs its scope. */
@RestController
@RequestMapping("/internal/persistence-consumer")
@ConditionalOnBooleanProperty("app.persistence-consumer.control.enabled")
@Hidden
class PersistenceConsumerController {

  private final PersistenceConsumer consumer;
  private final DeadLetterRedriveService redriveService;

  PersistenceConsumerController(
      PersistenceConsumer consumer, DeadLetterRedriveService redriveService) {
    this.consumer = consumer;
    this.redriveService = redriveService;
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

  /** Run once whatever refused the dead letters is fixed; a record refused again waits anew. */
  @PostMapping("/dead-letters/redrive")
  RedriveResponse redriveDeadLetters() {
    return new RedriveResponse(redriveService.redrive());
  }

  record StatusResponse(PersistenceConsumerStatus status) {}

  record RedriveResponse(int redrivenRecords) {}
}
