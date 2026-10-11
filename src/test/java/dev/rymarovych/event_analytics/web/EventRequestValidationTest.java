package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
            "evt_1", "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), null);

    assertThat(validator.validate(request)).isEmpty();
  }

  @Test
  void defaultsMissingPropertiesToEmptyObject() {
    var request =
        new EventRequest(
            "evt_1", "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), null);

    assertThat(request.properties()).isEqualTo(JsonNodeFactory.instance.objectNode());
  }

  @Test
  void rejectsBlankRequiredFields() {
    var request = new EventRequest("", "", "", null, null);

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("eventId", "userId", "eventType", "occurredAt");
  }

  @Test
  void acceptsValuesAtTheirLimits() {
    var earliest =
        new EventRequest(
            "e".repeat(EventRequest.MAX_EVENT_ID_LENGTH),
            "u".repeat(EventRequest.MAX_USER_ID_LENGTH),
            "t".repeat(EventRequest.MAX_EVENT_TYPE_LENGTH),
            Instant.parse("0001-01-01T00:00:00Z"),
            null);
    var latest =
        new EventRequest(
            "evt_1", "user_42", "page_view", Instant.parse("9999-12-31T23:59:59.999999999Z"), null);

    assertThat(validator.validate(earliest)).isEmpty();
    assertThat(validator.validate(latest)).isEmpty();
  }

  @Test
  void rejectsTextPastItsLength() {
    var request =
        new EventRequest(
            "e".repeat(EventRequest.MAX_EVENT_ID_LENGTH + 1),
            "u".repeat(EventRequest.MAX_USER_ID_LENGTH + 1),
            "t".repeat(EventRequest.MAX_EVENT_TYPE_LENGTH + 1),
            Instant.parse("2026-05-24T10:15:30Z"),
            null);

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("eventId", "userId", "eventType");
  }

  @Test
  void rejectsNulInEveryTextField() {
    var request =
        new EventRequest(
            "evt\u0000_1",
            "user\u0000_42",
            "page\u0000_view",
            Instant.parse("2026-05-24T10:15:30Z"),
            null);

    assertThat(validator.validate(request))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("eventId", "userId", "eventType");
  }

  @Test
  void rejectsNulInANestedPropertyNameOrString() {
    var json = JsonNodeFactory.instance;
    var nestedName = json.objectNode().set("context", json.objectNode().put("page\u0000url", "/"));
    var nestedString = json.objectNode().set("tags", json.arrayNode().add("ok").add("x\u0000y"));

    assertThat(validator.validate(withProperties(nestedName)))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("properties");
    assertThat(validator.validate(withProperties(nestedString)))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("properties");
  }

  @Test
  void rejectsUnpairedSurrogatesButAcceptsCompletePairs() {
    var unpaired =
        new EventRequest(
            "evt_\uDC00",
            "user_\uD800_42",
            "page_view",
            Instant.parse("2026-05-24T10:15:30Z"),
            JsonNodeFactory.instance.objectNode().put("label", "x\uD800"));
    var paired =
        new EventRequest(
            "evt_1",
            "user_\uD83D\uDE00",
            "page_view",
            Instant.parse("2026-05-24T10:15:30Z"),
            JsonNodeFactory.instance.objectNode().put("label", "\uD83D\uDE00"));

    assertThat(validator.validate(unpaired))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactlyInAnyOrder("eventId", "userId", "properties");
    assertThat(validator.validate(paired)).isEmpty();
  }

  @Test
  void rejectsFractionsPastDoubleRangeButKeepsBigIntegers() {
    var overflow = JsonMapper.shared().readTree("{\"amount\": {\"value\": 1e400}}");
    var bigInteger = JsonMapper.shared().readTree("{\"amount\": 1" + "0".repeat(400) + "}");

    assertThat(validator.validate(withProperties(overflow)))
        .extracting(v -> v.getPropertyPath().toString())
        .containsExactly("properties");
    assertThat(validator.validate(withProperties(bigInteger))).isEmpty();
  }

  @Test
  void rejectsTimestampsOutsideFourDigitYears() {
    for (String timestamp : new String[] {"0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z"}) {
      var request =
          new EventRequest("evt_1", "user_42", "page_view", Instant.parse(timestamp), null);

      assertThat(validator.validate(request))
          .extracting(v -> v.getPropertyPath().toString())
          .as(timestamp)
          .containsExactly("occurredAt");
    }
  }

  private static EventRequest withProperties(JsonNode properties) {
    return new EventRequest(
        "evt_1", "user_42", "page_view", Instant.parse("2026-05-24T10:15:30Z"), properties);
  }
}
