package com.cabin.orchestrator.weather;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Downloads hourly-or-better ASOS/AWOS observations from the Iowa Environmental Mesonet.
 * It is public, free and needs no key. Asks for UTC times so the stored data has no
 * daylight-saving ambiguity. A non-200 or an HTML error page is an IOException, so the
 * caller keeps what it already has instead of storing garbage.
 */
@Component
public class MesonetHttpFetcher implements WeatherFetcher {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public MesonetHttpFetcher(@Value("${cabin.weather.baseUrl:https://mesonet.agron.iastate.edu/cgi-bin/request/asos.py}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    static String url(String baseUrl, String station, LocalDate from, LocalDate to) {
        return baseUrl + "?station=" + station
            + "&data=tmpf&data=dwpf&data=relh"
            + "&year1=" + from.getYear() + "&month1=" + from.getMonthValue() + "&day1=" + from.getDayOfMonth()
            + "&year2=" + to.getYear() + "&month2=" + to.getMonthValue() + "&day2=" + to.getDayOfMonth()
            + "&tz=UTC&format=onlycomma&latlon=no&missing=empty&trace=T&direct=no"
            + "&report_type=3&report_type=4";
    }

    @Override
    public String fetch(String station, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(baseUrl, station, from, to)))
            .timeout(Duration.ofSeconds(90))
            .header("User-Agent", "cabin-orchestrator outdoor-baseline (contact: platform owner)")
            .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("Mesonet HTTP " + resp.statusCode());
        String body = resp.body();
        if (body == null || !body.contains("station,valid")) throw new IOException("Mesonet returned an unexpected body");
        return body;
    }
}
