package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.InvalidTenantZoneException;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Per-tenant settings, keyed by the same {@code source} the events carry.
 *
 * <p>Settings, not a registry: a tenant exists because it holds a token, so this is read on the
 * analytics path only and never consulted to decide whether a tenant is real.
 */
public interface TenantSettingsRepository {

  /**
   * The zone {@code source}'s time buckets are computed in, or empty if the tenant has no settings
   * row. Empty is an ordinary answer meaning "no zone configured" — the caller decides what to
   * bucket in — whereas a row holding a value that is not a zone raises {@link
   * InvalidTenantZoneException} rather than being treated as absent.
   */
  Optional<ZoneId> findBucketingZone(String source);
}
