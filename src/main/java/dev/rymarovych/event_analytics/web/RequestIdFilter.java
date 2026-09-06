package dev.rymarovych.event_analytics.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, so the lines it logs can be found together and quoted back.
 *
 * <p>The id goes into the logging context rather than through method signatures: the logging
 * framework reads that context when it formats a line, so everything logged while this request is
 * being handled carries the id without any of it knowing the id exists. It is removed in a {@code
 * finally} because a context left behind would label the next request with this one's id.
 *
 * <p>Ordered ahead of everything, which matters for exactly one case: {@code
 * spring.security.filter.order} defaults to -100, so a filter registered after it would leave every
 * rejected request — the 401s, the ones most worth correlating — with no id at all.
 *
 * <p>Which ids are accepted from a caller, and what a minted one looks like, is {@link RequestId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  static final String CONTEXT_KEY = "requestId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var requestId = RequestId.fromInboundHeader(request.getHeader(HEADER)).value();
    MDC.put(CONTEXT_KEY, requestId);
    response.setHeader(HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CONTEXT_KEY);
    }
  }
}
