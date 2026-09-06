package dev.rymarovych.event_analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins which inbound ids the service is willing to repeat.
 *
 * <p>The value arrives in a header, is written into every log line the request produces, and is
 * echoed back — so what it accepts is a question about forged log entries and log size, not about
 * tidiness. Each rejection below is a caller who gets a minted id instead, silently.
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
