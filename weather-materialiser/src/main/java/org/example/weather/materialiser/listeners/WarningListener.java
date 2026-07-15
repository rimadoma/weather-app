package org.example.weather.materialiser.listeners;

import org.example.weather.materialiser.schemas.WeatherWarning;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.db.generated.Tables.WEATHER_WARNINGS;

@Component
public class WarningListener {

    private static final Logger log = LoggerFactory.getLogger(WarningListener.class);
    private static final Set<String> SEVERITIES = Set.of("yellow", "orange", "red");

    private final DSLContext db;

    public WarningListener(DSLContext db) {
        this.db = db;
    }

    @RabbitListener(queues = "${weather.rabbitmq.warning-queue}")
    public void onMessage(String xml) {
        WeatherWarning message = Utils.parse(xml, WeatherWarning.class, log);
        if (message == null) {
            return;
        }

        String region = message.region() == null ? null : message.region().trim();
        if (region == null || region.isEmpty()) {
            log.warn("Skipping warning: no region");
            return;
        }

        String severity = message.severity() == null ? null : message.severity().trim().toLowerCase();
        if (severity == null || severity.isEmpty() || !SEVERITIES.contains(severity)) {
            log.warn("Skipping warning: invalid severity");
            return;
        }

        String description = message.description() == null ? null : message.description().trim();
        if (description == null || description.isEmpty()) {
            log.warn("Skipping warning: no description");
            return;
        }

        // In principle, we could skip expired warnings, but historical data may be needed at some point.
        // Plus there's never going to be a huge number of warnings.
        OffsetDateTime startTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(message.startTime()), ZoneOffset.UTC);
        OffsetDateTime endTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(message.endTime()), ZoneOffset.UTC);
        if (!endTime.isAfter(startTime)) {
            log.warn("Skipping warning: invalid timestamps");
            return;
        }

        Optional<Long> regionId = db.select(REGIONS.ID).from(REGIONS)
                .where(REGIONS.NAME.eq(region))
                .fetchOptional(REGIONS.ID);
        if (regionId.isEmpty()) {
            log.warn("Skipping warning: unknown region {}", region);
            return;
        }

        db.insertInto(WEATHER_WARNINGS)
                .columns(WEATHER_WARNINGS.REGION_ID,
                        WEATHER_WARNINGS.SEVERITY,
                        WEATHER_WARNINGS.DESCRIPTION,
                        WEATHER_WARNINGS.START_TIME,
                        WEATHER_WARNINGS.END_TIME)
                .values(regionId.get(), severity, description, startTime, endTime)
                .execute();

        log.info("Inserted a warning for region {} (id {})", region, regionId.get());
    }
}
