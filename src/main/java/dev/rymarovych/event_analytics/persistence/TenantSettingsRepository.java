package dev.rymarovych.event_analytics.persistence;

import dev.rymarovych.event_analytics.domain.InvalidTenantZoneException;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Per-tenant settings, keyed by the same {@code tenant_name} the events carry.
 *
 * <p>Settings, not a registry: a tenant exists because it holds a token, so this is read on the
 * analytics path only and never consulted to decide whether a tenant is real.
 */
public interface TenantSettingsRepository {

  /**
   * Return the configured zone, or empty when no settings row exists; the caller chooses the
   * default. Invalid stored values raise {@link InvalidTenantZoneException} rather than appearing
   * absent.
   */
  Optional<ZoneId> findBucketingZone(TenantName tenant);
}
