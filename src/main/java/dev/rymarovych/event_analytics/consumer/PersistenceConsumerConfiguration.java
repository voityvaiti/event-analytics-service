package dev.rymarovych.event_analytics.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Container settings for the persistence group beyond what application.yaml can express. */
@Configuration(proxyBeanMethods = false)
class PersistenceConsumerConfiguration {

  /**
   * The listener handles its own failures; should anything still escape it, retry forever rather
   * than the default of logging and skipping the records after ten attempts.
   */
  @Bean
  CommonErrorHandler neverSkippingErrorHandler() {
    return new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
  }
}
