package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.opportunities.AlertRecommendations.AlertNoise;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRecommendationsTest {

    private static AlertNoise noise(String kind, String ruleId, String condition, long raised, long silenced) {
        return new AlertNoise("key", kind, ruleId, condition, "dev", raised, silenced, null, null);
    }

    private static String joined(List<String> recs) {
        return String.join(" | ", recs).toLowerCase();
    }

    @Test
    void aMissedCheckinPointsAtTheCheckinWindowThenLinkAndBattery() {
        List<String> recs = AlertRecommendations.forAlert(noise("device", null, "MISSED_CHECKIN", 9, 0), false, 3);

        assertThat(joined(recs)).contains("check-in window", "battery");
        assertThat(joined(recs)).doesNotContain("silenced");
    }

    @Test
    void aSafetyClassAlertIsNeverToldToTuneItself() {
        for (AlertNoise safety : List.of(
                noise("device", null, "DEVICE_ALARM", 9, 5),
                noise("automation", "SAFETY_ALARM", null, 9, 5),
                noise("automation", "FREEZE_RISK", null, 9, 5))) {
            List<String> recs = AlertRecommendations.forAlert(safety, false, 3);
            assertThat(joined(recs)).as(safety.ruleId() + "/" + safety.condition()).contains("safety alert", "not a fix");
            assertThat(joined(recs)).doesNotContain("retire", "threshold change", "cooldown");
        }
    }

    @Test
    void anyAlertOnASafetyCategoryDeviceIsSafetyClass() {
        AlertNoise missed = noise("device", null, "MISSED_CHECKIN", 9, 0);

        assertThat(AlertRecommendations.isSafetyClass(missed, true)).isTrue();
        assertThat(joined(AlertRecommendations.forAlert(missed, true, 3))).contains("safety alert").doesNotContain("check-in window");
    }

    @Test
    void aWorkflowAlertSuggestsACooldownAndSaysExistingWorkflowsCannotBeEdited() {
        List<String> recs = AlertRecommendations.forAlert(noise("automation", "WORKFLOW_wf1", null, 8, 0), false, 3);

        assertThat(joined(recs)).contains("cooldown", "cannot be edited yet");
    }

    @Test
    void anUnconfirmedCommandPointsAtTheDeviceAndAnyCompetingAutomation() {
        List<String> recs = AlertRecommendations.forAlert(noise("automation", "WORKFLOW_UNCONFIRMED", null, 6, 0), false, 3);

        assertThat(joined(recs)).contains("not being confirmed", "reverting");
    }

    @Test
    void aPresenceRuleBlamesThePresencePictureFirst() {
        List<String> recs = AlertRecommendations.forAlert(noise("automation", "MOTION_WHILE_UNOCCUPIED", null, 6, 0), false, 3);

        assertThat(joined(recs)).contains("presence");
    }

    @Test
    void aBuiltInRuleSaysItsThresholdIsADeployTimeSetting() {
        List<String> recs = AlertRecommendations.forAlert(noise("automation", "WATER_PRESSURE_HIGH", null, 6, 0), false, 3);

        assertThat(joined(recs)).contains("deploy-time");
    }

    @Test
    void beingSilencedOftenAddsARetuneOrRetireNote_onlyAtOrAboveTheThreshold() {
        assertThat(joined(AlertRecommendations.forAlert(noise("device", null, "MISSED_CHECKIN", 2, 2), false, 3))).doesNotContain("retire");
        assertThat(joined(AlertRecommendations.forAlert(noise("device", null, "MISSED_CHECKIN", 2, 3), false, 3)))
            .contains("silenced 3 times", "retire");
    }
}
