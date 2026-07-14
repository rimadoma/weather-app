package org.example.weather.generator.publishers;

import org.example.weather.generator.schemas.WeatherWarning;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.xml.XmlMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.generator.utils.Helpers.WARNING_DESCRIPTIONS;

/**
 * Publishes a single {@code weather_warning} message on a rare schedul.
 * The warning starts now and is active for 24 -- 72 h.
 */
@Component
public class WarningPublisher {
    private static final Logger log = LoggerFactory.getLogger(WarningPublisher.class);
    private static final XmlMapper xmlMapper = new XmlMapper();
    private static final Random rng = new Random();
    private static final MessageProperties messageProperties = createProperties();

    private static final List<String> SEVERITIES = List.of("Yellow", "Orange", "Red");

    private final DSLContext db;
    private final AmqpTemplate amqpTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public WarningPublisher(DSLContext db, AmqpTemplate amqpTemplate, RabbitMqProperties rabbitMqProperties) {
        this.db = db;
        this.amqpTemplate = amqpTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    private static MessageProperties createProperties() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentEncoding("UTF-8");
        properties.setContentType(MessageProperties.CONTENT_TYPE_XML);
        return properties;
    }

    @Scheduled(fixedDelay = 330, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    private void publishWarning() {
        // Queried fresh each tick; the message resolves regions by name, so
        // pick from names that actually exist.
        String[] regionNames = db.select(REGIONS.NAME).from(REGIONS).fetchArray(REGIONS.NAME);
        if (regionNames.length == 0) {
            log.info("No regions found -- skipping this tick");
            return;
        }

        String region = regionNames[rng.nextInt(regionNames.length)];

        // At least one warning; 25 % chance of another for the same region,
        // each sent as its own message (and so on).
        do {
            String severity = SEVERITIES.get(rng.nextInt(SEVERITIES.size()));
            String description = WARNING_DESCRIPTIONS.get(rng.nextInt(WARNING_DESCRIPTIONS.size()));

            long start = Instant.now().getEpochSecond();
            long end = start + TimeUnit.HOURS.toSeconds(rng.nextInt(24, 73));

            log.info("Publishing {} warning for region {}: {}", severity, region, description);
            WeatherWarning warning = new WeatherWarning(region, severity, description, start, end);
            byte[] bytes = xmlMapper.writeValueAsString(warning).getBytes(StandardCharsets.UTF_8);
            Message message = new Message(bytes, messageProperties);

            amqpTemplate.send(rabbitMqProperties.metofficeExchange(), rabbitMqProperties.warningRoutingKey(), message);
        } while (rng.nextDouble() < 0.25);
    }
}
