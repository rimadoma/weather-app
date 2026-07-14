package org.example.weather.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class WeatherApiApplication {

    static void main(String[] args) {
        // Pin the JVM to UTC before Spring (and so the DB connection pool) starts.
        // measured_at is TIMESTAMPTZ, an instant with no stored zone; the pgjdbc
        // driver labels it using the JVM's default zone when it materialises an
        // OffsetDateTime. Without this, a machine in e.g. Europe/Helsinki serialises
        // bucket timestamps as +03:00 instead of the ISO-8601 UTC the API promises
        // (requirements iteration 5). Setting it here, rather than via a per-machine
        // JVM flag or env var, means there's nothing to remember per environment.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(WeatherApiApplication.class, args);
    }
}
