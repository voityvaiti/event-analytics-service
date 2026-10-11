package dev.rymarovych.event_analytics.consumer;

import java.util.Objects;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/** {@link PersistenceConsumer} driving the persistence listener's container. */
@Component
class ListenerContainerPersistenceConsumer implements PersistenceConsumer {

  private final KafkaListenerEndpointRegistry registry;

  ListenerContainerPersistenceConsumer(KafkaListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  @Override
  public PersistenceConsumerStatus start() {
    container().start();
    return status();
  }

  @Override
  public PersistenceConsumerStatus stop() {
    container().stop();
    return status();
  }

  /** STOPPED only once no consumer thread is still finishing its records. */
  @Override
  public PersistenceConsumerStatus status() {
    MessageListenerContainer container = container();
    if (container.isRunning()) {
      return PersistenceConsumerStatus.RUNNING;
    }
    return container.isChildRunning()
        ? PersistenceConsumerStatus.STOPPING
        : PersistenceConsumerStatus.STOPPED;
  }

  private MessageListenerContainer container() {
    return Objects.requireNonNull(
        registry.getListenerContainer(EventsRecordListener.LISTENER_ID),
        "The persistence listener is not registered");
  }
}
