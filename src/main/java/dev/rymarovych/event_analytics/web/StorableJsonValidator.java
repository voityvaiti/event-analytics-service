package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.StorableCharacters;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Checks {@link StorableJson}, walking every name, string and fractional number in the value. */
public class StorableJsonValidator implements ConstraintValidator<StorableJson, JsonNode> {

  @Override
  public boolean isValid(@Nullable JsonNode json, ConstraintValidatorContext context) {
    return json == null || isStorable(json);
  }

  private static boolean isStorable(JsonNode json) {
    if (json.isString()) {
      return StorableCharacters.isStorable(json.stringValue());
    }
    if (json.isFloatingPointNumber()) {
      return Double.isFinite(json.doubleValue());
    }
    return json.propertyNames().stream().allMatch(StorableCharacters::isStorable)
        && json.values().stream().allMatch(StorableJsonValidator::isStorable);
  }
}
