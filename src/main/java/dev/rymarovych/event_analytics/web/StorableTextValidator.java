package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.StorableCharacters;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/** Checks {@link StorableText}. */
public class StorableTextValidator implements ConstraintValidator<StorableText, String> {

  @Override
  public boolean isValid(@Nullable String text, ConstraintValidatorContext context) {
    return text == null || StorableCharacters.isStorable(text);
  }
}
