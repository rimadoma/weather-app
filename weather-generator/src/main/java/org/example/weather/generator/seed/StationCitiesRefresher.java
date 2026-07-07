package org.example.weather.generator.seed;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static org.example.weather.db.generated.Tables.STATION_CITIES;

/**
 * Refreshes the station_cities materialised view once, after cities and
 * stations are seeded. jOOQ has no typed REFRESH MATERIALISED VIEW API (only
 * CREATE/ALTER/COMMENT/DROP for materialised views) -- plain SQL, same
 * accepted exception as the earthdistance calls inside the view itself.
 * Runs after {@link StationSeeder}.
 */
@Component
@Order(4)
public class StationCitiesRefresher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StationCitiesRefresher.class);

    private final DSLContext db;

    public StationCitiesRefresher(DSLContext db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.execute("REFRESH MATERIALIZED VIEW " + STATION_CITIES.getName());
        log.info("Refreshed {}", STATION_CITIES.getName());
    }
}
