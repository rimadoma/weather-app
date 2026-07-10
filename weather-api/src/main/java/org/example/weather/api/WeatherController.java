package org.example.weather.api;

import org.example.weather.api.generated.api.WeatherApi;
import org.example.weather.api.generated.model.CitySummary;
import org.example.weather.api.generated.model.WeatherListResponse;
import org.example.weather.api.generated.model.WeatherPageMetadata;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.example.weather.api.Constants.PAGE_SIZE;
import static org.example.weather.db.generated.Tables.*;
import static org.jooq.impl.DSL.avg;

@RestController
public class WeatherController implements WeatherApi {

    private final DSLContext db;

    public WeatherController(DSLContext db) {
        this.db = db;
    }

    @Override
    public ResponseEntity<WeatherListResponse> getCurrentWeather(Integer page) {
        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        WeatherPageMetadata metadata = new WeatherPageMetadata(page, PAGE_SIZE);

        // Query cities
        int offset = (page - 1) * PAGE_SIZE;
        Map<Long, String> cities = db.select(CITIES.ID, CITIES.NAME)
                .from(CITIES)
                .orderBy(CITIES.NAME)
                .offset(offset)
                .limit(PAGE_SIZE)
                .fetchMap(CITIES.ID, CITIES.NAME);
        if (cities.isEmpty()) {
            return ResponseEntity.ok(new WeatherListResponse(metadata, new ArrayList<>()));
        }

        // Average temperature per city, joined through the materialised view
        Map<Long, BigDecimal> averageTemperatureByCity = db
                .select(STATION_CITIES.CITY_ID, avg(SCALAR_MEASUREMENTS.READING))
                .from(SCALAR_MEASUREMENTS)
                .join(STATION_CITIES).on(SCALAR_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                .where(
                        STATION_CITIES.CITY_ID.in(cities.keySet()),
                        SCALAR_MEASUREMENTS.TYPE.eq("temperature"),
                        SCALAR_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .groupBy(STATION_CITIES.CITY_ID)
                .fetchMap(STATION_CITIES.CITY_ID, avg(SCALAR_MEASUREMENTS.READING));

        List<CitySummary> summaries = cities.entrySet().stream()
                .map(entry -> {
                    Long cityId = entry.getKey();
                    BigDecimal averageTemperature = averageTemperatureByCity.get(cityId);
                    CitySummary summary = new CitySummary(cityId, entry.getValue(), Collections.emptyList());
                    summary.setTemperature(averageTemperature == null ? null : averageTemperature.doubleValue());
                    return summary;
                })
                .toList();

        return ResponseEntity.ok(new WeatherListResponse(metadata, summaries));
    }
}
