package org.example.weather.generator.seed;

import org.example.weather.db.generated.tables.records.StationsRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Random;

import static org.example.weather.db.generated.Tables.CITIES;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.generator.seed.Constants.UK_LAT_RANGE;
import static org.example.weather.generator.seed.Constants.UK_LNG_RANGE;

/**
 * Seeds a handful of stations per city, each with a generated serial number
 * and a location -- city name or coordinates. Runs after {@link CitySeeder}.
 */
@Component
@Order(3)
public class StationSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StationSeeder.class);
    // Doesn't guarantee that a city will get this many stations (random coords)
    private static final int STATIONS_GENERATED_PER_CITY = 5;
    private static final String SERIAL_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SERIAL_LENGTH = 11;

    private final DSLContext db;
    private final Random rng = new Random();

    public StationSeeder(DSLContext db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] cityNames = db.select(CITIES.NAME).from(CITIES).fetchArray(CITIES.NAME);

        // Station serials are random every run, so onConflictDoNothing alone
        // never dedupes reruns like it does for regions/cities -- this guard
        // stops repeated runs from piling up ever more stations.
        int existing = db.fetchCount(STATIONS);
        if (existing >= STATIONS_GENERATED_PER_CITY * cityNames.length) {
            log.info("Stations already seeded ({} rows) -- skipping", existing);
            return;
        }

        InsertValuesStep4<StationsRecord, String, String, BigDecimal, BigDecimal> insert =
                db.insertInto(STATIONS, STATIONS.SERIAL_NO, STATIONS.CITY_NAME, STATIONS.LAT, STATIONS.LNG);

        for (String _ : cityNames) {
            for (int i = 0; i < STATIONS_GENERATED_PER_CITY; i++) {
                String serialNo = randomSerialNo();

                boolean hasCityName = rng.nextBoolean();
                String stationCityName = null;
                BigDecimal lat = null;
                BigDecimal lng = null;
                if (hasCityName) {
                    stationCityName = cityNames[rng.nextInt(cityNames.length)];
                } else {
                    // Don't care if some stations land too far (> 25 km) from any city
                    lat = BigDecimal.valueOf(rng.nextDouble(UK_LAT_RANGE[0], UK_LAT_RANGE[1]));
                    lng = BigDecimal.valueOf(rng.nextDouble(UK_LNG_RANGE[0], UK_LNG_RANGE[1]));
                }

                insert.values(serialNo, stationCityName, lat, lng);
            }
        }

        int inserted = insert.onConflictDoNothing().execute();
        log.info("Seeded stations: {} inserted", inserted);
    }

    private String randomSerialNo() {
        StringBuilder serialNo = new StringBuilder(SERIAL_LENGTH);
        for (int i = 0; i < SERIAL_LENGTH; i++) {
            serialNo.append(SERIAL_CHARS.charAt(rng.nextInt(SERIAL_CHARS.length())));
        }
        return serialNo.toString();
    }
}
