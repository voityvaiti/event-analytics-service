package dev.rymarovych.event_analytics.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Checks {@link StorableTimestamp}: from the start of year 0001 to the end of year 9999 UTC. */
public class StorableTimestampValidator implements ConstraintValidator<StorableTimestamp, Instant> {

  private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant END = Instant.parse("+10000-01-01T00:00:00Z");

  @Override
  public boolean isValid(@Nullable Instant value, ConstraintValidatorContext context) {
    return value == null || (!value.isBefore(EARLIEST) && value.isBefore(END));
  }
}
