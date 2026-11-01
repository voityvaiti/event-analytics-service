package dev.rymarovych.event_analytics.service;

import dev.rymarovych.event_analytics.messaging.DeadLetterRedriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** {@link DeadLetterRedriveService} republishing dead letters onto events.raw. */
@Service
class RepublishingDeadLetterRedriveService implements DeadLetterRedriveService {

  private static final Logger log =
      LoggerFactory.getLogger(RepublishingDeadLetterRedriveService.class);

  private final DeadLetterRedriver redriver;

  RepublishingDeadLetterRedriveService(DeadLetterRedriver redriver) {
    this.redriver = redriver;
  }

  @Override
  public int redrive() {
    int redriven = redriver.redrive();
    log.info("Redrove {} dead-lettered records onto events.raw", redriven);
    return redriven;
  }
}
