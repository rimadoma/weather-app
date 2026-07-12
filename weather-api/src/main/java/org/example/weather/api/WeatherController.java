package org.example.weather.api;

import org.example.weather.api.generated.api.WeatherApi;
import org.example.weather.api.generated.model.CitySummary;
import org.example.weather.api.generated.model.WeatherListResponse;
import org.example.weather.api.generated.model.WeatherPageMetadata;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.example.weather.api.Constants.PAGE_SIZE;
import static org.example.weather.api.Constants.TOTAL_COUNT;
import static org.example.weather.db.generated.Tables.*;
import static org.jooq.impl.DSL.*;

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

        // Query wind measurements and calculate their averages
        Map<Long, Result<Record3<Long, BigDecimal, Short>>> citiesWinds = db
                .select(STATION_CITIES.CITY_ID, WIND_MEASUREMENTS.SPEED, WIND_MEASUREMENTS.DIRECTION)
                .from(WIND_MEASUREMENTS)
                .join(STATION_CITIES).on(WIND_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                .where(
                        STATION_CITIES.CITY_ID.in(cities.keySet()),
                        WIND_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .fetchGroups(STATION_CITIES.CITY_ID);
        Map<Long, Double> speedAverages = new HashMap<>();
        Map<Long, Short> directionAverages = new HashMap<>();
        citiesWinds.forEach((cityId, winds) -> {
            double avgSpeed = winds.stream().mapToDouble(wind -> wind.value2().doubleValue()).sum() / winds.size();
            speedAverages.put(cityId, avgSpeed);

            WindVector avgDirVector = new WindVector(0.0, 0.0);
            winds.stream().map(r -> WindVector.fromWeatherData(r.value3(), r.value2())).forEach(avgDirVector::add);
            short avgDirection = avgDirVector.toDegrees();
            directionAverages.put(cityId, avgDirection);
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

    private static class WindVector {
        private double x;
        private double y;

        private WindVector(double x, double y) {
            this.x = x;
            this.y = y;
        }

        private void add(WindVector v) {
            this.x += v.x;
            this.y += v.y;
        }

        private short toDegrees() {
            long degrees = Math.round(Math.toDegrees(Math.atan2(y, x)));
            return (short) Math.floorMod(degrees, 360);
        }

        private static WindVector fromWeatherData(short degrees, BigDecimal speed) {
            double radians = Math.toRadians(degrees);
            double x = Math.cos(radians) * speed.doubleValue();
            double y = Math.sin(radians) * speed.doubleValue();
            return new WindVector(x, y);
        }
    }
}
