package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.TimeGrouping;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code groupBy} query parameter of time-bucketed endpoints to {@link TimeGrouping}
 * case-insensitively, so the documented lowercase values ({@code hour|day}) match the uppercase
 * enum constants. An unrecognized value throws, which Spring surfaces as {@code 400 Bad Request}.
 */
@Component
class TimeGroupingConverter implements Converter<String, TimeGrouping> {

  @Override
  public TimeGrouping convert(String source) {
    return TimeGrouping.valueOf(source.trim().toUpperCase(Locale.ROOT));
  }
}
