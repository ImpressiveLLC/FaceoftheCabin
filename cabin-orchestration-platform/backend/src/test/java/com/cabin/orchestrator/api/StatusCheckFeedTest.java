package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.events.EventStreamBroadcaster;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusCheckFeedTest {
    @Test void completeFeedIsNotPaginatedAndUnknownLocationDoesNotLeakOtherLocations() {
        var service = mock(CabinEventService.class);
        var registry = new DeviceRegistry(List.of());
        var rows = java.util.stream.IntStream.range(0, 25).mapToObj(i ->
            new CabinEvent("event-" + i, "sensor", "AUTOMATION_ALERT", "CRITICAL", Instant.now(), Map.<String,Object>of())).toList();
        when(service.statusCheckEvents(any())).thenReturn(rows);
        var controller = new EventController(service, registry, mock(EventStreamBroadcaster.class));
        assertEquals(rows, controller.statusChecks(null));
        assertEquals(rows, controller.statusChecks("both"));
        assertTrue(controller.statusChecks("unknown").isEmpty());
    }
}
