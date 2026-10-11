package dev.rymarovych.event_analytics.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/** Checks {@link StorableText}. */
public class StorableTextValidator implements ConstraintValidator<StorableText, String> {

  @Override
  public boolean isValid(@Nullable String text, ConstraintValidatorContext context) {
    return text == null || isStorable(text);
  }

  /**
   * {@code codePoints()} combines valid pairs, leaving only unpaired halves typed as surrogates.
   */
  static boolean isStorable(String text) {
    return text.codePoints()
        .noneMatch(
            codePoint -> codePoint == 0 || Character.getType(codePoint) == Character.SURROGATE);
  }
}
