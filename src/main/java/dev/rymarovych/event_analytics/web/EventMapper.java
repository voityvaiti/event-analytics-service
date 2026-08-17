package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.NewEvent;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Maps inbound web payloads to persistence commands at the single web/service boundary.
 *
 * <p>{@code source} is copied straight from the request today and will switch to the authenticated
 * principal once JWT authentication is in place; that change lands here rather than in the
 * controller.
 */
@Mapper(componentModel = "spring")
interface EventMapper {

  NewEvent toNewEvent(EventRequest request);

  List<NewEvent> toNewEvents(List<EventRequest> requests);
}
