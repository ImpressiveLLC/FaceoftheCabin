package com.cabin.orchestrator.presence;

public enum PresenceProfile {
    AT_HOME, AT_CABIN, AWAY, BOTH_OCCUPIED;

    public String label() {
        return switch (this) {
            case AT_HOME       -> "At Home";
            case AT_CABIN      -> "At Cabin";
            case AWAY          -> "Away";
            case BOTH_OCCUPIED -> "Both Occupied";
        };
    }
}
