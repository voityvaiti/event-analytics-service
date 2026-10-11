package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.StorableCharacters;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Checks {@link StorableJson}, walking every name, string and number in the value. */
public class StorableJsonValidator implements ConstraintValidator<StorableJson, JsonNode> {

  static final int MAX_INTEGER_DIGITS = 1000;
  static final int MAX_FRACTION_DIGITS = 1000;

  @Override
  public boolean isValid(@Nullable JsonNode json, ConstraintValidatorContext context) {
    return json == null || isStorable(json);
  }

  private static boolean isStorable(JsonNode json) {
    if (json.isString()) {
      return StorableCharacters.isStorable(json.stringValue());
    }
    if (json.isNumber()) {
      return isWithinDigitLimits(json.decimalValue());
    }
    return json.propertyNames().stream().allMatch(StorableCharacters::isStorable)
        && json.values().stream().allMatch(StorableJsonValidator::isStorable);
  }

  /** Counts digits by value: {@code 1E+3}, {@code 1000} and {@code 1000.0} have the same limits. */
  private static boolean isWithinDigitLimits(BigDecimal number) {
    if ((long) number.precision() - number.scale() > MAX_INTEGER_DIGITS) {
      return false;
    }
    BigDecimal normalized = number.signum() == 0 ? number : number.stripTrailingZeros();
    return normalized.scale() <= MAX_FRACTION_DIGITS;
  }
}
