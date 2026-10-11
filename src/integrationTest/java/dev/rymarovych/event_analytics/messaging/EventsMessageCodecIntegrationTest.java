package dev.rymarovych.event_analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import dev.rymarovych.event_analytics.domain.MalformedMessageException;
import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** Round-trips messages through the application's own mapper, as publisher and consumer do. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventsMessageCodecIntegrationTest {

  @Autowired private EventsMessageCodec codec;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void decodesWhatItEncodedWithoutLosingPrecision() {
    var events =
        List.of(
            event(
                "evt_1",
                Instant.parse("2026-05-24T10:15:30.123456789Z"),
                "{\"amount\": 0.1234567890123456789, \"tags\": [1, [\"ü\", {\"n\": 1E+999}]]}"),
            event("evt_2", Instant.parse("0001-01-01T00:00:00Z"), "{}"));

    assertThat(codec.decode(codec.encode(events))).isEqualTo(events);
  }

  @Test
  void refusesToMixTenantsInOneMessage() {
    var mixed =
        List.of(
            event("evt_1", Instant.EPOCH, "{}"),
            new NewEvent(
                new TenantName("other"),
                "evt_2",
                "user_42",
                "page_view",
                Instant.EPOCH,
                jsonMapper.readTree("{}")));

    assertThatThrownBy(() -> codec.encode(mixed)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "not json",
        "{\"version\": 2, \"tenant\": \"web\", \"events\": []}",
        "{\"version\": 1, \"tenant\": \" \", \"events\": []}",
        "{\"version\": 1, \"tenant\": \"web\", \"events\": []}",
        "{\"version\": 1, \"tenant\": \"web\"}",
        "{\"version\": 1, \"tenant\": \"web\", \"events\": [{\"event_id\": \"evt_1\"}]}"
      })
  void reportsAnUnreadableRecordAsMalformed(String payload) {
    assertThatThrownBy(() -> codec.decode(payload.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(MalformedMessageException.class);
  }

  @Test
  void reportsARecordWithoutAValueAsMalformed() {
    assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(MalformedMessageException.class);
  }

  private NewEvent event(String eventId, Instant occurredAt, String properties) {
    return new NewEvent(
        new TenantName("web"),
        eventId,
        "user_42",
        "page_view",
        occurredAt,
        jsonMapper.readTree(properties));
  }
}
