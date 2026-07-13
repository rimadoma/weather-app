package org.example.weather.materialiser.listeners;

import org.example.weather.materialiser.schemas.StationRegistration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.example.weather.db.generated.tables.Stations.STATIONS;

@Component
public class RegistrationListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationListener.class);

    private final DSLContext db;

    public RegistrationListener(DSLContext db) {
        this.db = db;
    }

    @RabbitListener(queues = "${weather.rabbitmq.registration-queue}")
    public void onMessage(String xml) {
        StationRegistration message = Utils.parse(xml, StationRegistration.class, log);
        if (message == null) {
            return;
        }

        // Validate serial number
        String serialNumber;
        if (message.serialNumber() == null || message.serialNumber().isEmpty()) {
            log.warn("Skipping registration: no serial number");
            return;
        }
        serialNumber = message.serialNumber().substring(0, Math.min(message.serialNumber().length(), 50));

        // Validate location data
        if (message.location() == null) {
            log.warn("Skipping registration: no location");
            return;
        }
        BigDecimal lat = null;
        BigDecimal lon = null;
        try {
            StationRegistration.Coordinates coordinates = message.location().coordinates();
            if (coordinates != null) {
                lat = new BigDecimal(coordinates.latitude());
                lon = new BigDecimal(coordinates.longitude());
            }
        } catch (NumberFormatException | NullPointerException e) {
            // Bad coordinates -- fall back to the city name if we have one.
        }
        String city = message.location().city();
        boolean cityValid = city != null && !city.isEmpty();
        boolean coordsValid = lat != null && lon != null
                && lat.doubleValue() >= -90 && lat.doubleValue() <= 90
                && lon.doubleValue() >= -180 && lon.doubleValue() <= 180;
        if (!cityValid && !coordsValid) {
            log.warn("Skipping registration: no valid location data");
            return;
        }

        // Always write the full location, nulling whichever half didn't validate:
        // a re-registration then replaces it wholesale, never leaving a stale half.
        Map<Field<?>, Object> location = new LinkedHashMap<>();
        location.put(STATIONS.CITY_NAME, cityValid ? city : null);
        location.put(STATIONS.LAT, coordsValid ? lat : null);
        location.put(STATIONS.LNG, coordsValid ? lon : null);
        db.insertInto(STATIONS)
                .set(STATIONS.SERIAL_NO, serialNumber)
                .set(location)
                .onConflict(STATIONS.SERIAL_NO)
                .doUpdate()
                .set(location)
                .execute();

        db.execute("REFRESH MATERIALIZED VIEW station_cities");

        log.info("Registered station {}", serialNumber);
    }
}
