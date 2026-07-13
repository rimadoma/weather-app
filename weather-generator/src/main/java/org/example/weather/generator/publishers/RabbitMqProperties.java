package org.example.weather.generator.publishers;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weather.rabbitmq")
public record RabbitMqProperties(String stationsExchange, String temperatureRoutingKey, String windRoutingKey, String registrationRoutingKey) {}
