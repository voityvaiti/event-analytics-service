package dev.rymarovych.event_analytics.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Batch request envelope for EventRequest items. One size constraint produces one violation for
 * empty or oversized batches. The envelope allows future fields without changing the array
 * contract.
 *
 * <p>Valid cascades into elements, identifying failures by position, e.g. events[3].eventId.
 */
public record EventBatchRequest(
    @NotNull @Size(min = 1, max = EventBatchRequest.MAX_EVENTS) @Valid List<EventRequest> events) {

  /**
   * The largest batch the endpoint accepts. A constant rather than configuration because
   * {@code @Size} takes a compile-time constant; it bounds the work one request can ask for, which
   * nothing else on the write path does yet.
   */
  public static final int MAX_EVENTS = 1000;
}
