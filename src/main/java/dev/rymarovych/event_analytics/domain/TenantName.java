package dev.rymarovych.event_analytics.domain;

/**
 * Validated tenant identity from the token, carried through every layer. A dedicated type prevents
 * confusion with user IDs, event types, or URLs at the isolation boundary.
 *
 * <p>Reject blank values even outside token authentication. Persisted as tenant_name.
 */
public record TenantName(String value) {

  public TenantName {
    if (value.isBlank()) {
      throw new IllegalArgumentException("A tenant name cannot be blank");
    }
  }
}
