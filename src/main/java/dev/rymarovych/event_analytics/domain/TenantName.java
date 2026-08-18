package dev.rymarovych.event_analytics.domain;

/**
 * The tenant a request speaks for, taken from its token's claim and carried down every layer.
 *
 * <p>A type rather than a {@code String} because this value <em>is</em> the isolation boundary: it
 * scopes every query and is written onto every row, so a wrong one answers one tenant about
 * another's events. As a {@code String} it was interchangeable with an event type, a user id, or a
 * page URL — the compiler had nothing to check.
 *
 * <p>It also gives the not-blank rule a home. A token with a blank claim is rejected at
 * verification, before any of this runs, so the check here is the second line rather than the first
 * — and the one that still holds for a value that reached the domain some other way.
 *
 * <p>The database column is still {@code source}, which is the name {@code events} has carried
 * since the first migration. Renaming it is a migration and a rewrite of the perf suite's seed
 * vocabulary, so it is deliberately not bundled here.
 */
public record TenantName(String value) {

  public TenantName {
    if (value.isBlank()) {
      throw new IllegalArgumentException("A tenant name cannot be blank");
    }
  }
}
