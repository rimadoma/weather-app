package org.example.weather.generator.publishers;

import org.example.weather.generator.schemas.StationMeasurements;
import org.example.weather.generator.schemas.StationMeasurements.Measurement;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.example.weather.db.generated.Tables.STATIONS;

/**
 * Publishes weather measurements every 15 mins to their appropriate routes
 */
@Component
public class MeasurementPublisher {
    private static final Logger log = LoggerFactory.getLogger(MeasurementPublisher.class);
    private static final XmlMapper xmlMapper = new XmlMapper();
    private static final Random rng = new Random();
    private static final MessageProperties properties = createProperties();

    private final DSLContext db;
    private final AmqpTemplate amqpTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    private static MessageProperties createProperties() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setContentEncoding("UTF-8");
        properties.setContentType(MessageProperties.CONTENT_TYPE_XML);
        return properties;
    }

    public MeasurementPublisher(DSLContext db, AmqpTemplate amqpTemplate, RabbitMqProperties rabbitMqProperties) {
        this.db = db;
        this.amqpTemplate = amqpTemplate;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    @Scheduled(fixedDelay = 15, initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    private void publishMeasurements() {
        // Queried fresh every tick, not cached at startup, so newly
        // registered stations are picked up without going stale.
        String[] serialNos = db.select(STATIONS.SERIAL_NO).from(STATIONS).fetchArray(STATIONS.SERIAL_NO);
        if (serialNos.length == 0) {
            // Seeding hasn't populated any stations yet (or the DB is
            // freshly reset) -- nothing to publish this tick.
            log.info("No stations found -- skipping this tick");
            return;
        }

        // Leave 0 -- 10 % stations without data to simulate real world & help testing
        shuffle(serialNos);
        int minStations = (int) (serialNos.length * 0.9);
        int stationVariance = (int) (serialNos.length * 0.1) + 1;
        int nStations = rng.nextInt(stationVariance) + minStations;

        for (int i = 0; i < nStations; i++) {
            log.info("Publishing measurements for station: {}", serialNos[i]);

            boolean partialReadings = rng.nextDouble() < 0.05;
            boolean tempMissing = rng.nextBoolean();
            long nowEpoch = Instant.now().getEpochSecond();

            // Small change that either wind or temp is missing, so that we can test partial data
            if (!partialReadings || !tempMissing) {
                Message message = generateTempMessage(serialNos[i], nowEpoch);
                amqpTemplate.send(rabbitMqProperties.stationsExchange(), rabbitMqProperties.temperatureRoutingKey(), message);
            }

            if (!partialReadings || tempMissing) {
                Message windMessage = generateWindMessage(serialNos[i], nowEpoch);
                amqpTemplate.send(rabbitMqProperties.stationsExchange(), rabbitMqProperties.windRoutingKey(), windMessage);
            }
        }
    }

    public static void shuffle(String[] array) {
        int n = array.length;
        while (n > 1) {
            int k = rng.nextInt(n--);
            String temp = array[n];
            array[n] = array[k];
            array[k] = temp;
        }
    }

    private Message generateTempMessage(String stationSerialNo, long nowEpoch) {
        double celsius = rng.nextDouble(-5.0, 35.0);
        boolean fahrenheit = rng.nextBoolean();
        double reading = fahrenheit ? celsius * 9.0 / 5.0 + 32.0 : celsius;
        String unit = fahrenheit ? "°F" : "°C";
        Measurement temperature = new Measurement(
                "temperature", String.format("%.2f", reading), unit, nowEpoch);

        StationMeasurements measurements = new StationMeasurements(stationSerialNo);
        measurements.addMeasurement(temperature);

        byte[] bytes = xmlMapper.writeValueAsString(measurements).getBytes(StandardCharsets.UTF_8);
        return new Message(bytes, properties);
    }


    private Message generateWindMessage(String serialNo, long nowEpoch) {
        double metresPerSecond = rng.nextDouble() * 25.0;
        boolean mph = rng.nextBoolean();
        // 1 m/s = 2.2369 mph; the materialiser normalises mph back to m/s
        double speed = mph ? metresPerSecond * 2.2369362920544 : metresPerSecond;
        String speedUnit = mph ? "mph" : "m/s";
        Measurement windSpeed = new Measurement("wind_speed", String.format("%.1f", speed), speedUnit, nowEpoch);

        short direction = (short) rng.nextInt(360);
        Measurement windDirection = new Measurement("wind_direction", String.valueOf(direction), "degrees", nowEpoch);

        StationMeasurements measurements = new StationMeasurements(serialNo);
        measurements.addMeasurement(windSpeed);
        measurements.addMeasurement(windDirection);

        byte[] bytes = xmlMapper.writeValueAsString(measurements).getBytes(StandardCharsets.UTF_8);
        return new Message(bytes, properties);
    }
}
