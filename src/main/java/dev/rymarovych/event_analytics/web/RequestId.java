package dev.rymarovych.event_analytics.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The identifier one request is known by — the one its caller brought, or one minted for it.
 *
 * <p>An inbound id is honoured so that a caller or a proxy already tracking the request keeps a
 * single identifier across the hop. It is untrusted input with an unusual destination, though: it
 * is written into every line the request logs and echoed back in the response. A newline in it ends
 * a line and starts one the caller wrote, and an unbounded value multiplies the size of all of
 * them.
 *
 * <p>Hence the rule: letters, digits, hyphen and underscore, between 8 and 64 characters. The upper
 * bound leaves room for a UUID and for a W3C trace id; the lower one refuses a value too short to
 * identify anything. Anything else is discarded rather than trimmed — a truncated forgery is still
 * a forgery, and a silently altered id correlates nothing while looking as if it does. The response
 * then carries the id the service actually used, so a caller whose value was refused can see that.
 *
 * <p>A minted id satisfies the same rule, which is what keeps the two halves from drifting: the
 * next service to receive one applies this check to it.
 */
record RequestId(String value) {

  private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

  static RequestId fromInboundHeader(@Nullable String header) {
    return header != null && ACCEPTABLE.matcher(header).matches()
        ? new RequestId(header)
        : new RequestId(UUID.randomUUID().toString());
  }
}
