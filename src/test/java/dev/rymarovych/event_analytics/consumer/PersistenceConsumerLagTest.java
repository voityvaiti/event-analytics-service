package dev.rymarovych.event_analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

/** The perf harness waits for a lag of zero before it deletes, so an outage must not read as 0. */
class PersistenceConsumerLagTest {

  @Test
  void reportsNoFigureWhileTheBrokerIsUnreachable() {
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    var unreachable =
        new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"));

    try (var lag = new PersistenceConsumerLag(unreachable, meterRegistry)) {
      assertThat(meterRegistry.get("persistence.consumer.lag").gauge().value()).isNaN();
    }
  }
}
