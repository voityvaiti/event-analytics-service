package dev.rymarovych.event_analytics.domain;

/**
 * Validated tenant identity from the token, carried through every layer. A dedicated type prevents
 * confusion with user IDs, event types, or URLs at the isolation boundary.
 *
 * <p>Persisted as tenant_name; the same constraints apply outside token authentication.
 */
public record TenantName(String value) {

  /** Bounds this field's contribution to the shared tenant/time/type/user index entry. */
  public static final int MAX_LENGTH = 128;

  public TenantName {
    if (value.isBlank() || value.length() > MAX_LENGTH || !StorableCharacters.isStorable(value)) {
      throw new IllegalArgumentException(
          "A tenant name must be non-blank, at most "
              + MAX_LENGTH
              + " characters, and free of NUL and unpaired surrogates");
    }
  }
}
