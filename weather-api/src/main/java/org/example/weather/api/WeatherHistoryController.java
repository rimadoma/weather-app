package org.example.weather.api;

import org.example.weather.api.generated.api.WeatherHistoryApi;
import org.example.weather.api.generated.model.WeatherBucket;
import org.example.weather.api.generated.model.WeatherHistoryResponse;
import org.example.weather.db.toolbox.WindAggregates;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.example.weather.db.generated.Tables.*;
import static org.jooq.impl.DSL.avg;

@RestController
public class WeatherHistoryController implements WeatherHistoryApi {

    private final DSLContext db;

    public WeatherHistoryController(DSLContext db) {
        this.db = db;
    }

    private Field<OffsetDateTime> generateBucketExpression(TableField<? extends Record, OffsetDateTime> timestampField,
                                                           OffsetDateTime startTime) {
        return DSL.field(
                """
                        date_bin(
                            interval '6 hours',
                            {0},
                            {1}
                        )
                        """,
                OffsetDateTime.class,
                timestampField,
                startTime
        ).as("bouquet");
    }

    @Override
    public ResponseEntity<WeatherHistoryResponse> getWeatherHistory(Long id) {
        Optional<String> cityName = db.select(CITIES.NAME).from(CITIES).where(CITIES.ID.eq(id)).fetchOptionalInto(String.class);
        if (cityName.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Fixed 6h bucket grid (Helpers.bucketStarts). Its first entry -- the window
        // start, an aligned boundary -- doubles as the date_bin origin and the query's
        // lower bound; the grid itself drives the response so the shape is fixed.
        List<OffsetDateTime> bucketStarts = Helpers.bucketStarts(OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime startTime = bucketStarts.getFirst();
        Field<OffsetDateTime> dbBucket = generateBucketExpression(SCALAR_MEASUREMENTS.MEASURED_AT, startTime);
        Field<OffsetDateTime> dbBucket2 = generateBucketExpression(WIND_MEASUREMENTS.MEASURED_AT, startTime);

        // Query avg temps by bucket
        Result<Record2<OffsetDateTime, BigDecimal>> avgTemps =
                db.select(dbBucket,
                                avg(SCALAR_MEASUREMENTS.READING))
                        .from(SCALAR_MEASUREMENTS)
                        .join(STATION_CITIES).on(SCALAR_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                        .where(
                                STATION_CITIES.CITY_ID.eq(id),
                                SCALAR_MEASUREMENTS.TYPE.eq("temperature"),
                                SCALAR_MEASUREMENTS.MEASURED_AT.ge(startTime)
                        )
                        .groupBy(dbBucket)
                        .orderBy(dbBucket)
                        .fetch();

        // Query avg winds by bucket
        Result<Record4<OffsetDateTime, BigDecimal, BigDecimal, BigDecimal>> avgWinds = db.select(dbBucket2,
                        WindAggregates.meanSpeed(),
                        WindAggregates.directionVectorX(),
                        WindAggregates.directionVectorY())
                .from(WIND_MEASUREMENTS)
                .join(STATION_CITIES).on(WIND_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                .where(
                        STATION_CITIES.CITY_ID.eq(id),
                        WIND_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .groupBy(dbBucket2)
                .orderBy(dbBucket2)
                .fetch();

        // Map avgs to the response
        Map<Long, WeatherBucket> bucketsByTime = new HashMap<>();
        avgTemps.forEach(r -> {
            long key = r.value1().toEpochSecond();
            WeatherBucket bucket = bucketsByTime.computeIfAbsent(key, _ -> new WeatherBucket());
            bucket.setStartTime(r.value1());
            bucket.setTemperature(r.value2().doubleValue());
        });
        avgWinds.forEach(r -> {
            long key = r.value1().toEpochSecond();
            WeatherBucket bucket = bucketsByTime.computeIfAbsent(key, _ -> new WeatherBucket());
            bucket.setStartTime(r.value1());
            bucket.setWindSpeed(r.value2().doubleValue());
            double x = r.value3().doubleValue();
            double y = r.value4().doubleValue();
            int bearing = Helpers.bearingFromComponents(x, y);
            bucket.setWindDirection(bearing);
        });
        // Fill the fixed grid; empty buckets created with null readings (iteration 5)
        List<WeatherBucket> buckets = new ArrayList<>();
        for (OffsetDateTime bucketStart : bucketStarts) {
            WeatherBucket bucket = bucketsByTime.getOrDefault(bucketStart.toEpochSecond(), new WeatherBucket(bucketStart));
            buckets.add(bucket);
        }
        WeatherHistoryResponse response = new WeatherHistoryResponse(id, cityName.get(), new ArrayList<>(), buckets);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
