package com.cabin.orchestrator.devices.monitoring;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Preferences restrict a caller's existing projection; they never establish eligibility. */
public record MonitoringPreferences(String location, long revision, List<Card> cards) {
    public MonitoringPreferences { cards = List.copyOf(cards); }
    public record Card(String deviceId, Boolean included) {
        public Card { Objects.requireNonNull(included, "Explicit included value required"); }
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown card preference field: " + name);
        }
    }

    /** Pure shadow contract. No caller currently applies this to the production UI. */
    public Shadow shadow(List<String> projectedIds, Set<String> specificationIds) {
        var seen = new HashSet<String>();
        var collisions = new LinkedHashSet<String>();
        for (String id : projectedIds) if (!seen.add(id)) collisions.add(id);
        var visible = new LinkedHashSet<String>();
        var hidden = new HashSet<String>();
        for (Card card : cards) {
            if (!card.included()) hidden.add(card.deviceId());
            else if (seen.contains(card.deviceId())) visible.add(card.deviceId());
        }
        for (String id : projectedIds) if (!hidden.contains(id)) visible.add(id);
        var unknown = cards.stream().map(Card::deviceId).filter(id -> !seen.contains(id)).toList();
        var missing = projectedIds.stream().distinct().filter(id -> !specificationIds.contains(id)).toList();
        return new Shadow(List.copyOf(visible), missing, List.copyOf(collisions), unknown,
            !List.copyOf(visible).equals(projectedIds));
    }

    public record Shadow(List<String> visibleIds, List<String> missingSpecifications,
                         List<String> collisions, List<String> ignoredPreferenceIds, boolean changed) {}
}
