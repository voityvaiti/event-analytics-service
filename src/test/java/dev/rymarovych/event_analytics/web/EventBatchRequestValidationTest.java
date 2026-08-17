package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EventBatchRequestValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @Test
  void acceptsBatchOfValidEvents() {
    var request = new EventBatchRequest(List.of(event("evt_1"), event("evt_2")));

    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void acceptsBatchAtTheSizeCap() {
    var request = new EventBatchRequest(events(EventBatchRequest.MAX_EVENTS));

    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void rejectsEmptyBatch() {
    var request = new EventBatchRequest(List.of());

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("events");
  }

  @Test
  void rejectsBatchOverTheSizeCap() {
    var request = new EventBatchRequest(events(EventBatchRequest.MAX_EVENTS + 1));

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("events");
  }

  @Test
  void namesTheOffendingEventByItsPositionInTheBatch() {
    var invalid = new EventRequest("", "user_42", "page_view", null, null);
    var request = new EventBatchRequest(List.of(event("evt_1"), invalid, event("evt_3")));

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("events[1].eventId", "events[1].occurredAt");
  }

  private static EventRequest event(String eventId) {
    return new EventRequest(
        eventId, "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), null);
  }

  private static List<EventRequest> events(int count) {
    return IntStream.range(0, count).mapToObj(index -> event("evt_" + index)).toList();
  }
}
