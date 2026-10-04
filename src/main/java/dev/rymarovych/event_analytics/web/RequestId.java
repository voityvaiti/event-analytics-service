package dev.rymarovych.event_analytics.web;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Request correlation ID supplied by the caller or generated locally. Accept only 8–64 letters,
 * digits, hyphens, or underscores to bound log size and prevent log injection.
 *
 * <p>Replace invalid IDs rather than trimming them, which would break correlation. Echo the actual
 * ID in the response; generated IDs satisfy the same validation rule.
 */
record RequestId(String value) {

  private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

  static RequestId fromInboundHeader(@Nullable String header) {
    return header != null && ACCEPTABLE.matcher(header).matches()
        ? new RequestId(header)
        : new RequestId(UUID.randomUUID().toString());
  }
}
