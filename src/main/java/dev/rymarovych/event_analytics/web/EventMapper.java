package dev.rymarovych.event_analytics.web;

import dev.rymarovych.event_analytics.domain.NewEvent;
import dev.rymarovych.event_analytics.domain.TenantName;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps inbound web payloads to persistence commands at the single web/service boundary.
 *
 * <p>The tenant comes from the authenticated token rather than from the request, so it is a second
 * argument here rather than a field on {@link EventRequest}.
 */
@Mapper(componentModel = "spring")
interface EventMapper {

  @Mapping(target = "tenant", source = "tenant")
  NewEvent toNewEvent(EventRequest request, TenantName tenant);

  /**
   * Hand-written rather than generated: MapStruct does not thread an extra parameter through the
   * element mapping of an iterable method, and a {@code @Context} parameter cannot serve as a
   * {@code @Mapping} source. Every event in a batch takes the same tenant, so the loop is all the
   * generated version would have been.
   */
  default List<NewEvent> toNewEvents(List<EventRequest> requests, TenantName tenant) {
    return requests.stream().map(request -> toNewEvent(request, tenant)).toList();
  }
}
