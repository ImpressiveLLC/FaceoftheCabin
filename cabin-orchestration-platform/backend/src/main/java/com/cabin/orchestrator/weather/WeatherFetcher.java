package com.cabin.orchestrator.weather;

import java.io.IOException;
import java.time.LocalDate;

/** Source of raw observation CSV text. An interface so tests never touch the network. */
public interface WeatherFetcher {
    /** CSV (header plus rows, UTC times) for one station from {@code from} through {@code to}, inclusive days. */
    String fetch(String station, LocalDate from, LocalDate to) throws IOException, InterruptedException;
}
