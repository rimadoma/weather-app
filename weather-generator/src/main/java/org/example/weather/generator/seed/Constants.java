package org.example.weather.generator.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Fixed seed data shared by the seeders. */
final class Constants {

    static final String[] CITY_NAMES = loadCityNames();

    // Roughly the UK's bounding box; some resulting points end up in the sea.
    static final double[] UK_LAT_RANGE = {49.960000, 58.635000};
    static final double[] UK_LNG_RANGE = {-7.572168, 1.681530};

    private Constants() {
    }

    private static String[] loadCityNames() {
        try (InputStream in = Constants.class.getResourceAsStream("/city-names.txt")) {
            if (in == null) {
                throw new IllegalStateException("city-names.txt not found on classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toArray(String[]::new);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load city-names.txt", e);
        }
    }
}
