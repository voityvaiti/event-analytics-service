package dev.rymarovych.event_analytics.domain;

import java.util.List;

/**
 * Result of a top-pages aggregation: the most-referenced pages in ranking order, and whether more
 * ranked pages existed beyond the requested limit.
 */
public record TopPagesReport(List<PageCount> pages, boolean hasMore) {}
