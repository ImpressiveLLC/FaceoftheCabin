package com.cabin.orchestrator.weather;

import java.time.Instant;

/**
 * One hourly-or-better airport observation, stored in Celsius like every other
 * temperature this backend returns (the UI converts for display). Any of the three
 * readings can be null: the source marks a missing reading and a sensor can drop one
 * while still reporting the others.
 */
public record OutdoorObservation(String location, String station, Instant observedAt,
                                 Double tempC, Double dewpointC, Double rhPct) {}
