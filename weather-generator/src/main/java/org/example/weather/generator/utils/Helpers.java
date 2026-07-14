package org.example.weather.generator.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/** Fixed seed data and helper functions shared by the generator classes. */
public final class Helpers {

    public static final String[] CITY_NAMES = loadCityNames();

    /** Warning descriptions, shared by the warning seeder and publisher. */
    public static final List<String> WARNING_DESCRIPTIONS = List.of(
            "Flooding", "High winds", "Fire risk", "Extreme heat", "Heavy snow",
            "Ice", "Dense fog", "Thunderstorms", "Coastal overtopping",
            "Hedgehog uprising"
    );

    // Roughly the UK's bounding box; some resulting points end up in the sea.
    public static final double[] UK_LAT_RANGE = {49.960000, 58.635000};
    public static final double[] UK_LNG_RANGE = {-7.572168, 1.681530};

    private static final String SERIAL_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SERIAL_LENGTH = 11;

    private Helpers() {
    }

    /** Builds a random station serial number, drawing from the supplied {@link Random}. */
    public static String randomSerialNo(Random rng) {
        StringBuilder serialNo = new StringBuilder(SERIAL_LENGTH);
        for (int i = 0; i < SERIAL_LENGTH; i++) {
            serialNo.append(SERIAL_CHARS.charAt(rng.nextInt(SERIAL_CHARS.length())));
        }
        return serialNo.toString();
    }

    private static String[] loadCityNames() {
        try (InputStream in = Helpers.class.getResourceAsStream("/city-names.txt")) {
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
