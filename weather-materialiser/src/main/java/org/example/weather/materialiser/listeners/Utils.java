package org.example.weather.materialiser.listeners;

import org.example.weather.materialiser.schemas.StationMeasurements;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.example.weather.db.generated.Tables.STATIONS;

/**
 * Shared ingest helpers for the station-measurement listeners. Each returns a
 * value the caller inspects to decide whether to bail (null / empty list /
 * empty Optional). Helpers that detect a definite anomaly (malformed XML,
 * unknown station) log it via the caller's logger; interpreting an empty
 * filter result is left to the caller.
 */
final class Utils {

    private static final XmlMapper xmlMapper = new XmlMapper();

    private Utils() {}

    static <T> T parse(String xml, Class<T> clazz, Logger log) {
        try {
            return xmlMapper.readValue(xml, clazz);
        } catch (JacksonException e) {
            // Malformed message -- retrying won't fix it, so log and drop
            // rather than letting it propagate into a nack/requeue loop.
            // No parsed object to identify it by yet, so log the raw XML.
            log.error("Failed to parse message XML: {}", xml, e);
            return null;
        }
    }

    static List<StationMeasurements.Measurement> filterRelevantMeasures(StationMeasurements message, Set<String> types) {
        return message.measurements.stream()
                .filter(m -> types.contains(m.type()))
                .toList();
    }

    static Optional<Long> queryStationId(DSLContext db, String serialNumber, Logger log) {
        Optional<Long> stationId = db.select(STATIONS.ID).from(STATIONS)
                .where(STATIONS.SERIAL_NO.eq(serialNumber))
                .fetchOptional(STATIONS.ID);
        if (stationId.isEmpty()) {
            log.warn("Received data for an unknown station {}", serialNumber);
        }
        return stationId;
    }
}
