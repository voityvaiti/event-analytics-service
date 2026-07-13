package dev.rymarovych.event_analytics.domain;

import java.time.ZoneId;
import java.util.List;

/**
 * Result of an active-users aggregation: distinct users per time bucket, together with the time
 * zone the bucket boundaries were computed in.
 */
public record ActiveUsersReport(ZoneId zone, List<ActiveUsersBucket> buckets) {}
