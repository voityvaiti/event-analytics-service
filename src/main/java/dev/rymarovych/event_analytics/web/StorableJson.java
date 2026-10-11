package dev.rymarovych.event_analytics.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies {@link StorableText} to all JSON keys and strings. Numbers may have at most 1,000 digits
 * before and 1,000 after the point, excluding trailing fractional zeros; zero counts as written.
 * Parser limits also apply.
 */
@Documented
@Constraint(validatedBy = StorableJsonValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StorableJson {

  String message() default
      "must not contain NUL, unpaired surrogate characters, or numbers past 1000 digits before or"
          + " after the point";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
