package org.example.weather.generator.seeders;

import org.example.weather.db.generated.tables.records.RegionsRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.example.weather.db.generated.Tables.REGIONS;

/**
 * Seeds the fixed list of (real) UK counties used as regions. A single
 * multi-row INSERT built by looping .values() on one statement -- the
 * documented jOOQ idiom for bulk-inserting a dynamic number of rows.
 * onConflictDoNothing (regions.name is UNIQUE) makes re-running safe.
 * Runs before {@link CitySeeder}: cities.region_id is a NOT NULL FK.
 */
@Component
@Order(1)
public class RegionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RegionSeeder.class);

    private static final List<String> COUNTIES = List.of(
            "Northumberland", "Cumbria", "Durham", "North Yorkshire", "South Yorkshire",
            "West Yorkshire", "East Riding of Yorkshire", "Lancashire", "Greater Manchester",
            "Merseyside", "Cheshire", "Derbyshire", "Nottinghamshire", "Lincolnshire",
            "Leicestershire", "Staffordshire", "Shropshire", "Warwickshire", "Worcestershire",
            "Herefordshire", "Norfolk", "Suffolk", "Cambridgeshire", "Essex", "Hertfordshire",
            "Bedfordshire", "Buckinghamshire", "Oxfordshire", "Gloucestershire", "Somerset",
            "Devon", "Cornwall", "Dorset", "Wiltshire", "Hampshire", "Berkshire", "Surrey",
            "Kent", "East Sussex", "West Sussex"
    );

    private final DSLContext db;

    public RegionSeeder(DSLContext db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        InsertValuesStep1<RegionsRecord, String> insert = db.insertInto(REGIONS, REGIONS.NAME);
        COUNTIES.forEach(insert::values);

        int inserted = insert.onConflictDoNothing().execute();
        log.info("Seeded regions: {} inserted, {} already present", inserted, COUNTIES.size() - inserted);
    }
}
