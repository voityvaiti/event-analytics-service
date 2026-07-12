package dev.rymarovych.event_analytics.web;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code from}/{@code to} query parameters to an {@link Instant}.
 *
 * <p>The value must be an RFC 3339 timestamp — a strict ISO 8601 profile: a full date-time with a
 * mandatory offset, e.g. {@code 2026-05-24T10:00:00Z} or {@code 2026-05-24T12:00:00+02:00}. Parsing
 * uses {@link DateTimeFormatter#ISO_INSTANT}, which normalizes any offset to a UTC instant. A value
 * without an offset, or otherwise malformed, throws — Spring surfaces that as {@code 400 Bad
 * Request}.
 */
@Component
class InstantParameterConverter implements Converter<String, Instant> {

  @Override
  public Instant convert(String source) {
    return DateTimeFormatter.ISO_INSTANT.parse(source, Instant::from);
  }
}
