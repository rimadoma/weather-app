package org.example.weather.generator.publishers;

import org.example.weather.generator.schemas.StationRegistration;
import org.example.weather.generator.utils.Helpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.xml.XmlMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.example.weather.generator.utils.Helpers.*;

@Component
public class RegistrationPublisher {
    private static final Logger log = LoggerFactory.getLogger(RegistrationPublisher.class);
    private static final XmlMapper xmlMapper = new XmlMapper();
    private static final Random rng = new Random();
    private static final MessageProperties messageProperties = createProperties();

    private final AmqpTemplate amqpTemplate;
    private final RabbitMqProperties rabbitMqProperties;

    public RegistrationPublisher(AmqpTemplate amqpTemplate, RabbitMqProperties rabbitMqProperties) {
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

    // Long delay as a new station is a rare occurrence
    @Scheduled(fixedDelay = 270, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    private void publishRegistration() {
        // Generate location data
        boolean hasCityName = rng.nextBoolean();
        StationRegistration.Coordinates coordinates = null;
        String cityName = null;
        if (hasCityName) {
            cityName = CITY_NAMES[rng.nextInt(CITY_NAMES.length)];
        } else {
            BigDecimal lat = BigDecimal.valueOf(rng.nextDouble(UK_LAT_RANGE[0], UK_LAT_RANGE[1]));
            BigDecimal lng = BigDecimal.valueOf(rng.nextDouble(UK_LNG_RANGE[0], UK_LNG_RANGE[1]));
            coordinates = new StationRegistration.Coordinates(lat.toString(), lng.toString());
        }
        StationRegistration.Location location = new StationRegistration.Location(coordinates, cityName);

        // Generate serialNo
        String serialNo = null;
        if (rng.nextDouble() < 0.95) {
            // Small chance that serialNo is missing -- simulates dodgy data
            serialNo = Helpers.randomSerialNo(rng);
        }

        // Serialise data
        log.info("Publishing registration for station: {}", serialNo);
        StationRegistration registration = new StationRegistration(serialNo, location);
        byte[] bytes = xmlMapper.writeValueAsString(registration).getBytes(StandardCharsets.UTF_8);
        Message message = new Message(bytes, messageProperties);

        amqpTemplate.send(rabbitMqProperties.stationsExchange(), rabbitMqProperties.registrationRoutingKey(), message);
    }
}