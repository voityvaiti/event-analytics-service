package dev.rymarovych.event_analytics.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Restricts timestamps to years 0001–9999 UTC, within PostgreSQL and JDBC's finite range. */
@Documented
@Constraint(validatedBy = StorableTimestampValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StorableTimestamp {

  String message() default "must fall in the years 0001 to 9999 UTC";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
