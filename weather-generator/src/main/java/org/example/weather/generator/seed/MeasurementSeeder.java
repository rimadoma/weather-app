package org.example.weather.generator.seed;

import org.example.weather.db.generated.tables.records.ScalarMeasurementsRecord;
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
        // Wipe all existing data
        db.delete(SCALAR_MEASUREMENTS).execute();

        Long[] stationIds = db.select(STATIONS.ID).from(STATIONS).fetchArray(STATIONS.ID);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int inserted = 0;
        for (OffsetDateTime timestamp = now.minusHours(168); timestamp.isBefore(now); timestamp = timestamp.plusMinutes(15)) {
            InsertValuesStep4<ScalarMeasurementsRecord, String, BigDecimal, Long, OffsetDateTime> insertBatch = db.insertInto(SCALAR_MEASUREMENTS, SCALAR_MEASUREMENTS.TYPE, SCALAR_MEASUREMENTS.READING, SCALAR_MEASUREMENTS.STATION_ID, SCALAR_MEASUREMENTS.MEASURED_AT);
            for (Long stationId : stationIds) {
                BigDecimal temperature = BigDecimal.valueOf(rng.nextDouble(-5.0, 35.0));
                insertBatch.values("temperature", temperature, stationId, timestamp);
            }
            inserted += insertBatch.execute();
        }

        log.info("Seeded measurements: {} inserted", inserted);
    }
}
