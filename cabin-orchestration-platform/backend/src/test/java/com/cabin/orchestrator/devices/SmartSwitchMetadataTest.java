package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SmartSwitchMetadataTest {
    private DeviceStatus device(String id, String location, Map<String,Object> attrs) {
        return new DeviceStatus(id, DeviceType.HOME_ASSISTANT_ENTITY, id, "UNKNOWN", Instant.EPOCH, attrs, location);
    }
    @Test void renameProjectionPreservesAssignedIdentityAndSeparatesLocations() {
        var old=device("z2m-0x1234567890abcdef", "cabin", Map.of("deviceLifecycle","ASSIGNED","enabled",false));
        var next=device("z2m-new-label", "cabin", Map.of("ieeeAddress","0x1234567890abcdef","smartSwitch",true));
        var home=device("z2m-home-other", "home", Map.of("ieeeAddress","0x1234567890abcdef"));
        Map<String,Object> attrs=new HashMap<>(old.attributes());
        SmartSwitchMetadata.enrich(old,attrs,List.of(old,next,home));
        assertEquals(old.deviceId(),attrs.get("canonicalPhysicalId"));
        assertEquals(true,attrs.get("smartSwitch"));
        assertEquals(false,attrs.get("enabled"));
        assertNotEquals(SmartSwitchMetadata.identity(old),SmartSwitchMetadata.identity(home));
    }
    @Test void conflictingAssignmentsAreUnresolvedAndUnknownIdentityIsNotGuessed() {
        var a=device("z2m-a","cabin",Map.of("ieeeAddress","0x1234567890abcdef","deviceLifecycle","ASSIGNED"));
        var b=device("z2m-b","cabin",a.attributes());
        Map<String,Object> attrs=new HashMap<>();
        SmartSwitchMetadata.enrich(a,attrs,List.of(a,b));
        assertEquals("ambiguous",attrs.get("physicalIdentityStatus"));
        assertFalse(attrs.containsKey("canonicalPhysicalId"));
        assertEquals("",SmartSwitchMetadata.identity(device("z2m-label","cabin",Map.of())));
    }
}
