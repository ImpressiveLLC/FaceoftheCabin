package com.cabin.orchestrator.opportunities;

/**
 * Sprint 5 WSJF #1 (r5 handover). Only POWER_DRAW_ANOMALY ships in this
 * pass -- the handover's other starter type, COMFORT_DELTA (HVAC setpoint
 * vs. ambient), is deliberately deferred: zero real thermostats exist
 * anywhere in this codebase (both THERMOSTAT descriptors in
 * DeviceRegistry are disabled, home-location, pending hardware), so it
 * would ship as dead code with nothing to ever trigger it. Revisit once a
 * real thermostat is deployed.
 *
 * The handover's other starter type, ENERGY_IDLE ("device drawing power
 * during unoccupied hours = waste"), was also not built as specified --
 * see OptimizationAnalyticsService's own javadoc for why that premise is
 * actively unsafe against the two real POWER_METER devices that exist
 * today (one of them is the mechanical-room freeze-prevention heater,
 * which is *supposed* to draw power while the cabin is unoccupied in cold
 * weather). POWER_DRAW_ANOMALY is a corrected, self-relative signal
 * instead: never asserts "this is waste," only "this pattern is worth a
 * look" -- true and useful for every device type, including the heater.
 */
public enum OpportunityType {
    POWER_DRAW_ANOMALY
}
