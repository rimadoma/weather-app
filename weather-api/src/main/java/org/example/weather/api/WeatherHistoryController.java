package org.example.weather.api;

import org.example.weather.api.generated.api.WeatherHistoryApi;
import org.example.weather.api.generated.model.WeatherHistoryResponse;
import org.example.weather.db.toolbox.WindAggregates;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.example.weather.db.generated.Tables.*;
import static org.jooq.impl.DSL.avg;

@RestController
public class WeatherHistoryController implements WeatherHistoryApi {

    private final DSLContext db;

    public WeatherHistoryController(DSLContext db) {
        this.db = db;
    }

    @Override
    public ResponseEntity<WeatherHistoryResponse> getWeatherHistory(Long id) {
        // TODO Slice 4: build the fixed 6-hour bucket grid (boundaries 02/08/14/20
        // UTC), 24 complete buckets plus the current partial one, oldest first;
        // temperature + wind aggregates per bucket, nulls for empty buckets --
        // see requirements iteration 5.
        Optional<String> cityName = db.select(CITIES.NAME).from(CITIES).where(STATION_CITIES.CITY_ID.eq(id)).fetchOptionalInto(String.class);
        if (cityName.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        OffsetDateTime startTime = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7).withHour(22).withMinute(0).withSecond(0).withNano(0);

        Field<OffsetDateTime> bucket = DSL.field(
                """
                date_bin(
                    interval '6 hours',
                    {0},
                    {1}
                )
                """,
                OffsetDateTime.class,
                SCALAR_MEASUREMENTS.MEASURED_AT,
                startTime
        );

        Result<Record2<OffsetDateTime, BigDecimal>> avgTempBuckets =
                db.select(bucket.as("bucket"),
                        avg(SCALAR_MEASUREMENTS.READING))
                .from(SCALAR_MEASUREMENTS)
                .where(
                        STATION_CITIES.CITY_ID.eq(id),
                        SCALAR_MEASUREMENTS.TYPE.eq("temperature"),
                        SCALAR_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .groupBy(bucket)
                .orderBy(bucket)
                .fetch();

        Result<Record4<OffsetDateTime, BigDecimal, BigDecimal, BigDecimal>> avgWindBuckets = db.select(bucket.as("bucket"),
                        WindAggregates.meanSpeed(),
                        WindAggregates.directionVectorX(),
                        WindAggregates.directionVectorY())
                .from(WIND_MEASUREMENTS)
                .join(STATION_CITIES).on(WIND_MEASUREMENTS.STATION_ID.eq(STATION_CITIES.STATION_ID))
                .where(
                        STATION_CITIES.CITY_ID.eq(id),
                        WIND_MEASUREMENTS.MEASURED_AT.ge(startTime)
                )
                .groupBy(bucket)
                .orderBy(bucket)
                .fetch();



        throw new UnsupportedOperationException("GET /api/weather/{id} not implemented yet");
    }
}
