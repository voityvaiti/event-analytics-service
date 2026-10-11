package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** MockMvc always sends Content-Length, so the counting path is exercised here directly. */
class RequestBodyLimitFilterTest {

  private static final FilterChain READS_THE_WHOLE_BODY =
      (request, response) -> request.getInputStream().readAllBytes();

  private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

  @Test
  void stopsABodyWithoutContentLengthOncePastTheLimit() {
    var request =
        withoutContentLength("/api/v1/events/batch", RequestBodyLimitFilter.MAX_BODY_BYTES + 1);

    assertThatThrownBy(
            () -> filter.doFilter(request, new MockHttpServletResponse(), READS_THE_WHOLE_BODY))
        .isInstanceOf(RequestBodyTooLargeException.class);
  }

  @Test
  void passesABodyWithoutContentLengthAtTheLimit() {
    var request = withoutContentLength("/api/v1/events", RequestBodyLimitFilter.MAX_BODY_BYTES);

    assertThatNoException()
        .isThrownBy(
            () -> filter.doFilter(request, new MockHttpServletResponse(), READS_THE_WHOLE_BODY));
  }

  @Test
  void leavesOtherPathsAlone() {
    var request =
        withoutContentLength("/api/v1/stats/top-pages", 2 * RequestBodyLimitFilter.MAX_BODY_BYTES);

    assertThatNoException()
        .isThrownBy(
            () -> filter.doFilter(request, new MockHttpServletResponse(), READS_THE_WHOLE_BODY));
  }

  private static MockHttpServletRequest withoutContentLength(String path, long bytes) {
    var request =
        new MockHttpServletRequest("POST", path) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(new byte[Math.toIntExact(bytes)]);
    return request;
  }
}
