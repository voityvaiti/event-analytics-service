package dev.rymarovych.event_analytics.web;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Parse from/to as RFC 3339 timestamps with mandatory offsets, using DateTimeFormatter.ISO_INSTANT
 * to normalize to UTC. Invalid or offset-free values become 400 Bad Request.
 */
@Component
class InstantParameterConverter implements Converter<String, Instant> {

  @Override
  public Instant convert(String source) {
    return DateTimeFormatter.ISO_INSTANT.parse(source, Instant::from);
  }
}
