package dev.rymarovych.event_analytics.domain;

import java.io.Serial;

/**
 * Signals an unusable stored reporting zone. Persistence raises it and the web layer maps it to a
 * response. Fail instead of silently falling back to UTC and reporting against the wrong calendar.
 */
public class InvalidTenantZoneException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final String zone;

  public InvalidTenantZoneException(TenantName tenant, String zone, Throwable cause) {
    super(
        "Tenant '"
            + tenant.value()
            + "' has a stored reporting time zone that is not one: '"
            + zone
            + "'",
        cause);
    this.zone = zone;
  }

  /** The stored value that could not be read as a zone. */
  public String zone() {
    return zone;
  }
}
