package org.example.weather.api;

import org.example.weather.api.generated.api.WeatherApi;
import org.example.weather.api.generated.model.CitySummary;
import org.example.weather.api.generated.model.WeatherListResponse;
import org.example.weather.api.generated.model.WeatherPageMetadata;
import org.example.weather.db.toolbox.WindAggregates;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.example.weather.api.Helpers.PAGE_SIZE;
import static org.example.weather.api.Helpers.TOTAL_COUNT;
import static org.example.weather.db.generated.Tables.*;
import static org.jooq.impl.DSL.avg;
import static org.jooq.impl.DSL.count;

@RestController
public class WeatherController implements WeatherApi {

    private final DSLContext db;

    public WeatherController(DSLContext db) {
        this.db = db;
    }

    @Override
    public ResponseEntity<WeatherListResponse> getCurrentWeather(Integer page) {
        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        // Query cities for this page, plus the total city count via a window function
        int offset = (page - 1) * PAGE_SIZE;
        Result<Record3<Long, String, Integer>> cityRecords = db
                .select(CITIES.ID, CITIES.NAME, count().over().as(TOTAL_COUNT))
                .from(CITIES)
                .orderBy(CITIES.NAME)
                .offset(offset)
                .limit(PAGE_SIZE)
                .fetch();
        final int totalCount;
        final WeatherPageMetadata metadata;
        if (cityRecords.isEmpty()) {
            // Fall back to a plain count
            totalCount = db.fetchCount(CITIES);
            metadata = new WeatherPageMetadata(page, PAGE_SIZE, totalCount);
            return ResponseEntity.ok(new WeatherListResponse(metadata, new ArrayList<>()));
        } else {
            totalCount = cityRecords.getFirst().value3();
            metadata = new WeatherPageMetadata(page, PAGE_SIZE, totalCount);
        }
        Map<Long, String> cities = new LinkedHashMap<>();
        cityRecords.forEach(record -> cities.put(record.value1(), record.value2()));

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

        // Wind averages per city: SQL does the speed mean and the speed-weighted
        // direction-vector sums (weather-db toolbox); turning the summed
        // components into a bearing is read-side business logic (WindDirection).
        Map<Long, Double> speedAverages = new HashMap<>();
        Map<Long, Short> directionAverages = new HashMap<>();
        db.select(STATION_CITIES.CITY_ID,
                        WindAggregates.meanSpeed(),
                        WindAggregates.directionVectorX(),
                        WindAggregates.directionVectorY())
                .from(WIND_MEASUREMENTS)
                .join(STATION_CITIES).on(WIND_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                .where(
                        STATION_CITIES.CITY_ID.in(cities.keySet()),
                        WIND_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .groupBy(STATION_CITIES.CITY_ID)
                .fetch()
                .forEach(record -> {
                    Long cityId = record.value1();
                    speedAverages.put(cityId, record.value2().doubleValue());
                    directionAverages.put(cityId,
                            Helpers.bearingFromComponents(record.value3().doubleValue(), record.value4().doubleValue()));
                });

        // Build response
        List<CitySummary> summaries = cities.entrySet().stream()
                .map(entry -> {
                    Long cityId = entry.getKey();

                    BigDecimal averageTemperature = averageTemperatureByCity.get(cityId);
                    CitySummary summary = new CitySummary(cityId, entry.getValue(), Collections.emptyList());
                    summary.setTemperature(averageTemperature == null ? null : averageTemperature.doubleValue());

                    Double speed = speedAverages.get(cityId);
                    Short direction = directionAverages.get(cityId);
                    if (speed != null && direction != null) {
                        summary.setWindSpeed(speed);
                        summary.setWindDirection(Integer.valueOf(direction));
                    }

                    return summary;
                })
                .toList();

        return ResponseEntity.ok(new WeatherListResponse(metadata, summaries));
    }
}
