package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/**
 * Thrown when a tenant's stored reporting time zone is not a time zone this service can use.
 *
 * <p>It lives in {@code domain} for the reason {@link AnalyticsQueryTimeoutException} does: the
 * persistence layer raises it when it reads the settings row, and the web layer maps it to a
 * response.
 *
 * <p>That this is an exception rather than a fallback to UTC is the point. A tenant configured in
 * one zone and bucketed in another gets figures that are plausible and wrong, which is the failure
 * mode this system is built to avoid; refusing to answer is the loud alternative.
 */
public class InvalidTenantZoneException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String zone;

  public InvalidTenantZoneException(String source, String zone, Throwable cause) {
    super(
        "Tenant '" + source + "' has a stored reporting time zone that is not one: '" + zone + "'",
        cause);
    this.zone = zone;
  }

  /** The stored value that could not be read as a zone. */
  public String zone() {
    return zone;
  }
}
