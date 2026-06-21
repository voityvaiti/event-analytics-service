package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class EventRequestValidationTest {

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
  void acceptsFullyPopulatedRequest() {
    var request =
        new EventRequest(
            "web", "evt_1", "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), null);

    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void defaultsMissingPropertiesToEmptyObject() {
    var request =
        new EventRequest(
            "web", "evt_1", "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), null);

    assertThat(request.properties()).isEqualTo(JsonNodeFactory.instance.objectNode());
  }

  @Test
  void rejectsBlankRequiredFields() {
    var request = new EventRequest("", "", "", "", null, null);

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("source", "eventId", "userId", "eventType", "occurredAt");
  }
}
