package dev.rymarovych.event_analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Pins that the application's Kafka configuration reaches the test broker. Every Kafka client the
 * service creates inherits these bootstrap servers, so a broken service connection would otherwise
 * surface only as publish and consume timeouts in unrelated tests. Comparing addresses matters:
 * without the service connection the default {@code localhost:9092} reaches the Compose broker on a
 * developer machine and the test would still pass.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaServiceConnectionIntegrationTest {

  @Autowired private KafkaAdmin kafkaAdmin;
  @Autowired private KafkaContainer kafkaContainer;

  @Test
  void reachesTheContainerBrokerThroughTheServiceConnection() {
    assertThat(
            String.valueOf(
                kafkaAdmin
                    .getConfigurationProperties()
                    .get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)))
        .contains(kafkaContainer.getBootstrapServers());
    assertThat(kafkaAdmin.clusterId()).isNotBlank();
  }
}
