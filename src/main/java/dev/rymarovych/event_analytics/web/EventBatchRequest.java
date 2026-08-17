package dev.rymarovych.event_analytics.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Inbound payload for a batch ingestion request: the same events {@link EventRequest} describes,
 * many per request.
 *
 * <p>The size bound is one constraint rather than {@code @NotEmpty} plus a maximum, so that an
 * empty batch and an oversized one are each one violation, and the message quotes the range the
 * endpoint actually accepts instead of claiming zero is allowed.
 *
 * <p>An object rather than a bare JSON array, so a later addition to the request does not break the
 * contract — the same reason the read responses are enveloped.
 *
 * <p>{@code @Valid} on the list is what cascades validation into the elements, and with it the
 * offending field arrives position-indexed ({@code events[3].eventId}), which is the only way an
 * all-or-nothing rejection can say which event it objected to.
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
