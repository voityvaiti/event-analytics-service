package dev.rymarovych.event_analytics.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a single {@code /stats} aggregation may run before the database cancels it.
 *
 * <p>The default sits above the widest legitimate query the read path is known to serve — {@code
 * active-users} over a 30-day window costs ~3.6s against the 20M-row reference corpus — so the
 * timeout cuts off pathological work rather than expensive-but-honest work. Lowering it past that
 * cost starts rejecting queries the system is expected to answer; raising it lets a single query
 * hold a pooled connection for longer than a client will wait.
 *
 * <p>{@code 0} disables the timeout, matching Postgres' own semantics for {@code
 * statement_timeout}, which is how an unprotected arm is measured: {@code scripts/actions/start
 * --args='--analytics.query.timeout=0'}.
 */
@ConfigurationProperties("analytics.query")
record AnalyticsQueryProperties(@DefaultValue("5s") Duration timeout) {}
