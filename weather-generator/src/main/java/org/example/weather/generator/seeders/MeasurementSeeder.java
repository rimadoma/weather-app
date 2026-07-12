package org.example.weather.generator.seeders;

import org.example.weather.db.generated.tables.records.ScalarMeasurementsRecord;
import org.example.weather.db.generated.tables.records.WindMeasurementsRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Random;

import static org.example.weather.db.generated.Tables.SCALAR_MEASUREMENTS;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.db.generated.Tables.WIND_MEASUREMENTS;

@Component
@Order(5)
public class MeasurementSeeder implements ApplicationRunner {

    private final DSLContext db;
    private final Random rng = new Random();
    private static final Logger log = LoggerFactory.getLogger(MeasurementSeeder.class);

    public MeasurementSeeder(DSLContext db) {
        this.db = db;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        db.transaction(transaction -> {
            DSLContext ctx = transaction.dsl();

            // Wipe all existing data
            ctx.delete(SCALAR_MEASUREMENTS).execute();
            ctx.delete(WIND_MEASUREMENTS).execute();

            Long[] stationIds = ctx.select(STATIONS.ID).from(STATIONS).fetchArray(STATIONS.ID);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            int inserted = 0;
            for (OffsetDateTime timestamp = now.minusHours(168); timestamp.isBefore(now); timestamp = timestamp.plusMinutes(15)) {
                InsertValuesStep4<ScalarMeasurementsRecord, String, BigDecimal, Long, OffsetDateTime> insertBatch = ctx.insertInto(SCALAR_MEASUREMENTS, SCALAR_MEASUREMENTS.TYPE, SCALAR_MEASUREMENTS.READING, SCALAR_MEASUREMENTS.STATION_ID, SCALAR_MEASUREMENTS.MEASURED_AT);
                InsertValuesStep4<WindMeasurementsRecord, Long, BigDecimal, Short, OffsetDateTime> windBatch = ctx.insertInto(WIND_MEASUREMENTS, WIND_MEASUREMENTS.STATION_ID, WIND_MEASUREMENTS.SPEED, WIND_MEASUREMENTS.DIRECTION, WIND_MEASUREMENTS.MEASURED_AT);
                for (Long stationId : stationIds) {
                    BigDecimal temperature = BigDecimal.valueOf(rng.nextDouble(-5.0, 35.0));
                    insertBatch.values("temperature", temperature, stationId, timestamp);
                    BigDecimal windSpeed = BigDecimal.valueOf(rng.nextDouble(0.0, 25.0));
                    short windDirection = (short) rng.nextInt(360);
                    windBatch.values(stationId, windSpeed, windDirection, timestamp);
                }
                inserted += insertBatch.execute();
                inserted += windBatch.execute();
            }

            log.info("Seeded measurements: {} inserted", inserted);
        });
    }
}
