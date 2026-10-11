package dev.rymarovych.event_analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class ListenerContainerPersistenceConsumerTest {

  private final MessageListenerContainer container = mock(MessageListenerContainer.class);
  private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
  private final ListenerContainerPersistenceConsumer consumer =
      new ListenerContainerPersistenceConsumer(registry);

  @BeforeEach
  void registerTheContainer() {
    when(registry.getListenerContainer(EventsRecordListener.LISTENER_ID)).thenReturn(container);
  }

  @Test
  void reportsRunningWhileTheContainerRuns() {
    when(container.isRunning()).thenReturn(true);

    assertThat(consumer.status()).isEqualTo(PersistenceConsumerStatus.RUNNING);
  }

  @Test
  void reportsStoppingWhileAConsumerThreadStillFinishesItsRecords() {
    when(container.isRunning()).thenReturn(false);
    when(container.isChildRunning()).thenReturn(true);

    assertThat(consumer.stop()).isEqualTo(PersistenceConsumerStatus.STOPPING);
    verify(container).stop();
  }

  @Test
  void reportsStoppedOnceNoConsumerThreadIsAtWork() {
    when(container.isRunning()).thenReturn(false);
    when(container.isChildRunning()).thenReturn(false);

    assertThat(consumer.status()).isEqualTo(PersistenceConsumerStatus.STOPPED);
  }
}
