package org.example.weather.generator.seeders;

import org.example.weather.db.generated.tables.records.CitiesRecord;
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

import org.example.weather.generator.utils.Helpers;

import static org.example.weather.db.generated.Tables.CITIES;
import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.generator.utils.Helpers.*;

/**
 * Seeds one city per name in {@link Helpers#CITY_NAMES}, each assigned a
 * region and coordinates. Runs after {@link RegionSeeder}, before
 * {@link StationSeeder}.
 */
@Component
@Order(2)
public class CitySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CitySeeder.class);

    private final DSLContext db;

    public CitySeeder(DSLContext db) {
        this.db = db;
    }

    private final Random rng = new Random();

    @Override
    public void run(ApplicationArguments args) {
        Long[] regionsIds = db.select(REGIONS.ID).from(REGIONS).fetchArray(REGIONS.ID);

        int region = 0;
        InsertValuesStep4<CitiesRecord, String, Long, BigDecimal, BigDecimal> insert = db.insertInto(CITIES, CITIES.NAME, CITIES.REGION_ID, CITIES.LAT, CITIES.LNG);
        for (String name : CITY_NAMES) {
            // Even distribution of cities per region on purpose
            long regionId = regionsIds[region];
            BigDecimal lat = BigDecimal.valueOf(rng.nextDouble(UK_LAT_RANGE[0], UK_LAT_RANGE[1]));
            BigDecimal lng = BigDecimal.valueOf(rng.nextDouble(UK_LNG_RANGE[0], UK_LNG_RANGE[1]));

            insert.values(name, regionId, lat, lng);

            region++;
            region = region % regionsIds.length;
        }

        int inserted = insert.onConflictDoNothing().execute();
        log.info("Seeded cities: {} inserted, {} already present", inserted, CITY_NAMES.length - inserted);
    }
}
