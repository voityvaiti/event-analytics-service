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
 * Set the response request ID and logging context, then clear context in finally to prevent leakage
 * between requests.
 *
 * <p>Run before Spring Security (default order -100) so authentication failures also have IDs.
 * RequestId defines validation and generation.
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
