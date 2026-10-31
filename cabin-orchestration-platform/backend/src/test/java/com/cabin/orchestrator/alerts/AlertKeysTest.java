package com.cabin.orchestrator.alerts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertKeysTest {

    @Test
    void parsesADeviceKeyIntoDeviceAndCondition() {
        AlertKeys.Parsed p = AlertKeys.parse("device:z2m-leak_mech_room:missed-checkin");
        assertEquals("device", p.kind());
        assertEquals("z2m-leak_mech_room", p.deviceId());
        assertEquals("MISSED_CHECKIN", p.condition());
        assertEquals("DEVICE_ALARM", AlertKeys.parse("device:x:alarm").condition());
    }

    @Test
    void parsesAnAutomationKeyAndTreatsNoneAsNoDevice() {
        AlertKeys.Parsed p = AlertKeys.parse("automation:FREEZE_RISK:z2m-temp_outside_lowest");
        assertEquals("automation", p.kind());
        assertEquals("FREEZE_RISK", p.ruleId());
        assertEquals("z2m-temp_outside_lowest", p.deviceId());
        assertNull(AlertKeys.parse("automation:WORKFLOW_x:none").deviceId());
    }

    @Test
    void anUnrecognizedOrNullKeyIsUnknownNotAnError() {
        assertEquals("unknown", AlertKeys.parse("something else").kind());
        assertEquals("unknown", AlertKeys.parse(null).kind());
        assertEquals("device", AlertKeys.parse("device:nocolon").kind());
    }

    @Test
    void buildsTheSameKeyTheClientBuildsForOrdinaryRules() {
        assertEquals("automation:WORKFLOW_wf1:ha-cabin-x", AlertKeys.automation("WORKFLOW_wf1", "ha-cabin-x"));
        assertEquals("automation:FREEZE_RISK:none", AlertKeys.automation("FREEZE_RISK", null));
    }

    @Test
    void everyUnconfirmedCommandAlertSharesOneKeySoRepeatsCanBeCounted() {
        assertEquals(AlertKeys.automation("WORKFLOW_UNCONFIRMED_exec-1", "valve"),
                     AlertKeys.automation("WORKFLOW_UNCONFIRMED_exec-2", "valve"));
    }
}
