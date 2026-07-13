package dev.rymarovych.event_analytics.domain;

/** One row of a top-pages aggregation: how many events referenced a page. */
public record PageCount(String pageUrl, long count) {}
