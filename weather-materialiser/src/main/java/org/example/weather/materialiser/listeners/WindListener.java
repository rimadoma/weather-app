package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.WindMeasurementsRecord;
import org.example.weather.materialiser.schemas.StationMeasurements;
import org.example.weather.materialiser.schemas.StationMeasurements.Measurement;
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
import java.util.*;

import static org.example.weather.db.generated.Tables.WIND_MEASUREMENTS;

@Component
public class WindListener {

    private static final Logger log = LoggerFactory.getLogger(WindListener.class);

    private final DSLContext db;

    public WindListener(DSLContext db) {
        this.db = db;
    }

    @RabbitListener(queues = "${weather.rabbitmq.wind-queue}")
    public void onMessage(String xml) {
        StationMeasurements message = Utils.parse(xml, StationMeasurements.class, log);
        if (message == null) {
            return;
        }

        Optional<Long> stationId = Utils.queryStationId(db, message.serialNumber, log);
        if (stationId.isEmpty()) {
            return;
        }

        List<Measurement> measurements = Utils.filterRelevantMeasures(message, Set.of("wind_speed", "wind_direction"));
        if (measurements.isEmpty()) {
            log.error("Received no wind measurements for station {}", message.serialNumber);
            return;
        }

        Map<Long, BigDecimal> speedsByTimestamp = new HashMap<>();
        Map<Long, Short> directionsByTimestamp = new HashMap<>();
        for (Measurement measurement : measurements) {
            if ("wind_speed".equalsIgnoreCase(measurement.type())) {
                BigDecimal speed = parseSpeed(measurement);
                if (speed != null) {
                    speedsByTimestamp.put(measurement.timestamp(), speed);
                }
            } else if ("wind_direction".equalsIgnoreCase(measurement.type())) {
                Short direction = parseDirection(measurement);
                if (direction != null) {
                    directionsByTimestamp.put(measurement.timestamp(), direction);
                }
            }
        }

        Map<Long, WindDatum> windsByTimestamp = new HashMap<>();
        speedsByTimestamp.forEach((timestamp, speed) -> {
            Short direction = directionsByTimestamp.get(timestamp);
            if (direction != null) {
                windsByTimestamp.put(timestamp, new WindDatum(speed, direction));
            }
        });

        InsertValuesStep4<WindMeasurementsRecord, BigDecimal, Short, Long, OffsetDateTime> insert = db.insertInto(WIND_MEASUREMENTS,
                WIND_MEASUREMENTS.SPEED,
                WIND_MEASUREMENTS.DIRECTION,
                WIND_MEASUREMENTS.STATION_ID,
                WIND_MEASUREMENTS.MEASURED_AT);
        windsByTimestamp.forEach((timestamp, wind) -> {
            OffsetDateTime dateTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
            insert.values(wind.speed, wind.direction, stationId.get(), dateTime);
        });

        int inserts = insert.execute();
        if (inserts > 0) {
            log.info("Wrote {} measurements for station {}", inserts, stationId.get());
        } else {
            log.warn("Received no valid wind measurements for station {}", stationId.get());
        }
    }


    private BigDecimal parseSpeed(Measurement speed) {
        double value;
        try {
            value = Double.parseDouble(speed.value());
        } catch (NumberFormatException e) {
            log.error("Error parsing wind speed value {}", speed.value(), e);
            return null;
        }

        if (value < 0) {
            log.warn("Skipping negative speed {}", speed.value());
            return null;
        } else if ("mph".equals(speed.unit())) {
            value = value / 2.2369362920544;
        } else if (!"m/s".equals(speed.unit())) {
            log.warn("Unsupported unit: {}", speed.unit());
            return null;
        }

        return BigDecimal.valueOf(value);
    }

    private Short parseDirection(Measurement direction) {
        short value;
        try {
            value = Short.parseShort(direction.value(), 10);
        } catch (NumberFormatException e) {
            log.error("Error parsing wind direction value {}", direction.value(), e);
            return null;
        }

        if (value < 0 || value >= 360) {
            log.warn("Skipping invalid direction {}", direction.value());
            return null;
        } else if (!"degrees".equalsIgnoreCase(direction.unit())) {
            log.warn("Unsupported unit: {}", direction.unit());
            return null;
        }

        return value;
    }

    private record WindDatum(BigDecimal speed, Short direction) {
    }
}
