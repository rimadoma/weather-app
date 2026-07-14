package org.example.weather.generator.seeders;

import org.example.weather.db.generated.tables.records.WeatherWarningsRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep5;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.db.generated.Tables.WEATHER_WARNINGS;
import static org.example.weather.generator.utils.Helpers.WARNING_DESCRIPTIONS;

/**
 * Seeds a batch of warnings that are active right now, so the read path has
 * something to show before any live {@code weather_warning} messages flow.
 * <p>
 * Warnings live at region granularity, so we pick roughly a quarter of all
 * regions straight from the regions table and give each one warning. That in
 * principle allows a warning for a region with no cities, but it keeps the
 * seeder independent of the station_cities matview, and currently no region is
 * empty -- {@link CitySeeder} spreads cities evenly across every region.
 * <p>
 * Re-runs wipe and reseed (like {@link MeasurementSeeder}) to avoid data bloat.
 */
@Component
@Order(6)
public class WarningSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarningSeeder.class);

    private static final List<String> SEVERITIES = List.of("yellow", "orange", "red");

    private final DSLContext db;
    private final Random rng = new Random();

    public WarningSeeder(DSLContext db) {
        this.db = db;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        db.transaction(transaction -> {
            DSLContext ctx = transaction.dsl();

            ctx.delete(WEATHER_WARNINGS).execute();

            List<Long> regionIds = new ArrayList<>(
                    ctx.select(REGIONS.ID).from(REGIONS).fetch(REGIONS.ID));
            Collections.shuffle(regionIds, rng);
            int warned = Math.max(1, regionIds.size() / 4);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            InsertValuesStep5<WeatherWarningsRecord, Long, String, String, OffsetDateTime, OffsetDateTime> insert =
                    ctx.insertInto(WEATHER_WARNINGS,
                            WEATHER_WARNINGS.REGION_ID, WEATHER_WARNINGS.DESCRIPTION,
                            WEATHER_WARNINGS.SEVERITY, WEATHER_WARNINGS.START_TIME, WEATHER_WARNINGS.END_TIME);

            for (Long regionId : regionIds.subList(0, warned)) {
                // At least one warning; 25 % chance of piling on another (and so on).
                do {
                    String description = WARNING_DESCRIPTIONS.get(rng.nextInt(WARNING_DESCRIPTIONS.size()));
                    String severity = SEVERITIES.get(rng.nextInt(SEVERITIES.size()));
                    // Started up to a day ago, still running for up to two more days.
                    OffsetDateTime start = now.minusHours(rng.nextInt(1, 25));
                    OffsetDateTime end = now.plusHours(rng.nextInt(1, 49));
                    insert.values(regionId, description, severity, start, end);
                } while (rng.nextDouble() < 0.25);
            }

            int inserted = insert.execute();
            log.info("Seeded warnings: {} inserted across {}/{} regions",
                    inserted, warned, regionIds.size());
        });
    }
}
