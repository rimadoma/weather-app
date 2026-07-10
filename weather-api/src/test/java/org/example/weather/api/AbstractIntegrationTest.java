package org.example.weather.api;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class AbstractIntegrationTest {

    // Singleton container: started once for the whole test JVM and never
    // stopped explicitly -- it's cleaned up at JVM
    // exit. Avoids paying startup cost per test class.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }
}
