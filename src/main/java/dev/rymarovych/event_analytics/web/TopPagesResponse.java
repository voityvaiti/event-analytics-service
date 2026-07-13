package dev.rymarovych.event_analytics.web;

import java.time.Instant;
import java.util.List;

/**
 * Response envelope for a top-pages query. Echoes the effective query so the result is
 * self-describing: the {@code from}/{@code to} window and {@code limit} (which reflects the applied
 * default). {@code hasMore} signals the ranking was truncated at {@code limit}.
 */
public record TopPagesResponse(
    Instant from, Instant to, int limit, boolean hasMore, List<Page> pages) {

  /** One ranked page: the {@code page_url} property value and how many events referenced it. */
  public record Page(String pageUrl, long count) {}
}
