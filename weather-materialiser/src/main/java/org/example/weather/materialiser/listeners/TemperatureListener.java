package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.ScalarMeasurementsRecord;
import org.example.weather.materialiser.schemas.StationMeasurements;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.example.weather.db.generated.tables.ScalarMeasurements.SCALAR_MEASUREMENTS;

@Component
public class TemperatureListener {

    private static final Logger log = LoggerFactory.getLogger(TemperatureListener.class);

    private final DSLContext db;

    public TemperatureListener(DSLContext db) {
        this.db = db;
    }

    @RabbitListener(queues = "${weather.rabbitmq.temperature-queue}")
    public void onMessage(String xml) {
        StationMeasurements message = Utils.parse(xml, log);
        if (message == null) {
            return;
        }

        List<StationMeasurements.Measurement> measurements = Utils.filterRelevantMeasures(message, Set.of("temperature"));
        if (measurements.isEmpty()) {
            log.error("Received no temperature measurements for station {}", message.serialNumber);
            return;
        }

        Optional<Long> stationId = Utils.queryStationId(db, message.serialNumber, log);
        if (stationId.isEmpty()) {
            return;
        }

        InsertValuesStep4<ScalarMeasurementsRecord, String, BigDecimal, Long, OffsetDateTime> insert = db.insertInto(SCALAR_MEASUREMENTS, SCALAR_MEASUREMENTS.TYPE, SCALAR_MEASUREMENTS.READING, SCALAR_MEASUREMENTS.STATION_ID, SCALAR_MEASUREMENTS.MEASURED_AT);
        int valuesAdded = 0;
        for (StationMeasurements.Measurement measurement : measurements) {
            double value;
            try {
                value = Double.parseDouble(measurement.value());
            } catch (NumberFormatException e) {
                log.error("Invalid reading value '{}' for station {}", measurement.value(), message.serialNumber);
                continue;
            }

            String unit = measurement.unit();
            BigDecimal degrees;
            if ("°F".equals(unit)) {
                degrees = BigDecimal.valueOf((value - 32.0) * 5.0 / 9.0);
            } else if ("°C".equals(unit)) {
                degrees = BigDecimal.valueOf(value);
            } else {
                log.error("Can't normalise unit {} for station {}", unit, message.serialNumber);
                continue;
            }

            OffsetDateTime timestamp = OffsetDateTime.ofInstant(Instant.ofEpochSecond(measurement.timestamp()), ZoneOffset.UTC);

            insert.values("temperature", degrees, stationId.get(), timestamp);
            valuesAdded++;
        }

        if (valuesAdded == 0) {
            log.warn("No measurements with a recognised unit for station {}", message.serialNumber);
            return;
        }

        int inserts = insert.execute();
        log.info("Wrote {} measurements for station {}", inserts, stationId.get());
    }
}
