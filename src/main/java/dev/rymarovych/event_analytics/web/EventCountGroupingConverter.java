package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.EventCountGrouping;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code groupBy} query parameter to {@link EventCountGrouping} case-insensitively, so
 * the documented lowercase values ({@code type|hour|day}) match the uppercase enum constants. An
 * unrecognized value throws, which Spring surfaces as {@code 400 Bad Request}.
 */
@Component
class EventCountGroupingConverter implements Converter<String, EventCountGrouping> {

  @Override
  public EventCountGrouping convert(String source) {
    return EventCountGrouping.valueOf(source.trim().toUpperCase(Locale.ROOT));
  }
}
