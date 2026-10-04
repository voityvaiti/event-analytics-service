package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validate IDs before echoing them in headers and logs, preventing log forgery and excessive size.
 * Invalid input is replaced with a generated ID.
 */
class RequestIdTest {

  @Test
  void keepsAnIdTheCallerAlreadyTracks() {
    assertThat(RequestId.fromInboundHeader("checkout-7f3a91"))
        .isEqualTo(new RequestId("checkout-7f3a91"));
  }

  @Test
  void mintsOneWhenTheHeaderIsAbsent() {
    assertThat(RequestId.fromInboundHeader(null).value()).isNotBlank();
  }

  @Test
  void refusesAnIdCarryingANewline() {
    var forged = "abc12345\nWARN  nothing actually failed";

    assertThat(RequestId.fromInboundHeader(forged).value()).isNotEqualTo(forged);
  }

  @Test
  void refusesAnIdLongEnoughToBloatEveryLine() {
    assertThat(RequestId.fromInboundHeader("a".repeat(65)).value()).hasSize(36);
  }

  @Test
  void refusesAnIdTooShortToIdentifyAnything() {
    assertThat(RequestId.fromInboundHeader("abc").value()).isNotEqualTo("abc");
  }

  /** A minted id crossing another service's hop has to survive the same rule this one applies. */
  @Test
  void mintsIdsItWouldItselfAccept() {
    var minted = RequestId.fromInboundHeader(null);

    assertThat(RequestId.fromInboundHeader(minted.value())).isEqualTo(minted);
  }

  @Test
  void mintsADistinctIdPerRequest() {
    assertThat(RequestId.fromInboundHeader(null)).isNotEqualTo(RequestId.fromInboundHeader(null));
  }
}
