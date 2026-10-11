package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rymarovych.event_analytics.domain.MessageTooLargeException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * The 512 KiB body limit keeps HTTP from reaching the 768 KiB message limit, so it is called here.
 */
class ApiExceptionHandlerTest {

  @Test
  void answersAMessageOverTheBrokerLimitAsTooLarge() {
    var request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/events/batch"));

    var response =
        new ApiExceptionHandler()
            .handleMessageTooLarge(new MessageTooLargeException("too large"), request);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(response.getBody())
        .isInstanceOfSatisfying(
            ProblemDetail.class,
            problem -> {
              assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
              assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/events/batch"));
            });
  }
}
