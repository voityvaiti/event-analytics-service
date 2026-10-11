package dev.rymarovych.event_analytics.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every property name and string in the JSON value, at any depth, meets {@link StorableText}, and
 * no fractional number is past double range. The parser reads fractions as doubles, so an overflow
 * would become infinity and be written as the string {@code "Infinity"}. Fractions within range are
 * kept at double precision, which can round digits the client sent; integers parse exactly and are
 * not checked.
 */
@Documented
@Constraint(validatedBy = StorableJsonValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StorableJson {

  String message() default
      "must not contain NUL, unpaired surrogate characters, or fractions beyond double range";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
