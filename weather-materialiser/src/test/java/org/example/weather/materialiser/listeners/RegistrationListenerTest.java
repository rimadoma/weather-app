package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.StationsRecord;
import org.example.weather.materialiser.AbstractIntegrationTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.weather.db.generated.Tables.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@Transactional
class RegistrationListenerTest extends AbstractIntegrationTest {

    private static final String SERIAL = "REG-001";

    @Autowired
    private RegistrationListener listener;

    @Autowired
    private TemperatureListener temperatureListener;

    @Autowired
    private DSLContext db;

    // --- Happy path: best-effort. Write when a city OR a valid coordinate pair
    // survives; a rubbish other half is ignored, not fatal. ---
    @ParameterizedTest(name = "registers a station given {0}")
    @MethodSource("validLocations")
    void registersStation(String label, String locationXml, String expectedCity, String expectedLat, String expectedLng) {
        listener.onMessage(registrationXml(SERIAL, locationXml));

        StationsRecord row = db.selectFrom(STATIONS).where(STATIONS.SERIAL_NO.eq(SERIAL)).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getCityName()).isEqualTo(expectedCity);
        assertCoordinate(row.getLat(), expectedLat);
        assertCoordinate(row.getLng(), expectedLng);
    }

    private static Stream<Arguments> validLocations() {
        return Stream.of(
                Arguments.of("a city name",
                        location("<city>Testville</city>"), "Testville", null, null),
                Arguments.of("coordinates",
                        location(coordinates("54.5", "-1.2")), null, "54.5", "-1.2"),
                Arguments.of("both a city and coordinates",
                        location("<city>Testville</city>" + coordinates("54.5", "-1.2")), "Testville", "54.5", "-1.2"),
                // Best-effort: the city is valid, the half coordinate is junk and dropped.
                Arguments.of("a valid city and half coordinates",
                        location("<city>Testville</city><coordinates><latitude>54.5</latitude></coordinates>"),
                        "Testville", null, null),
                // Best-effort: the coordinates are valid, the blank city is junk and dropped.
                Arguments.of("valid coordinates and a blank city",
                        location("<city></city>" + coordinates("54.5", "-1.2")), null, "54.5", "-1.2")
        );
    }

    // --- Skips: nothing usable, or input that would otherwise trip a DB CHECK.
    // The listener must validate and bail cleanly -- no row, no thrown exception. ---
    @ParameterizedTest(name = "skips {0}")
    @MethodSource("invalidRegistrations")
    void skipsInvalidRegistration(String label, String xml) {
        listener.onMessage(xml);

        assertThat(db.fetchCount(STATIONS)).isZero();
    }

    private static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("a missing serial number",
                        registrationXml(null, location("<city>Testville</city>"))),
                Arguments.of("a blank serial number",
                        registrationXml("", location("<city>Testville</city>"))),
                Arguments.of("no location at all",
                        registrationXml(SERIAL, "<location></location>")),
                Arguments.of("half coordinates and no city",
                        registrationXml(SERIAL, location("<coordinates><latitude>54.5</latitude></coordinates>"))),
                Arguments.of("non-numeric coordinates and no city",
                        registrationXml(SERIAL, location(coordinates("not-a-number", "-1.2")))),
                // lat 200 would violate the -90..90 CHECK -- must be caught before the DB.
                Arguments.of("out-of-range coordinates and no city",
                        registrationXml(SERIAL, location(coordinates("200", "0")))),
                // Malformed XML: dropped entire, and must not throw.
                Arguments.of("malformed XML", "<station><serialNumber>" + SERIAL)
        );
    }

    // --- Upsert: re-registration replaces the location, never duplicates the row. ---
    @Test
    void upsertsOnReRegistration() {
        listener.onMessage(registrationXml(SERIAL, location("<city>Testville</city>")));
        listener.onMessage(registrationXml(SERIAL, location(coordinates("54.5", "-1.2"))));

        List<StationsRecord> rows = db.selectFrom(STATIONS).where(STATIONS.SERIAL_NO.eq(SERIAL)).fetch();
        assertThat(rows).hasSize(1);
        StationsRecord row = rows.getFirst();
        // Replace semantics: the coords-only re-registration overwrites the whole
        // location, clearing the previously-registered city -- no stale half left behind.
        assertThat(row.getCityName()).isNull();
        assertThat(row.getLat()).isEqualByComparingTo("54.5");
        assertThat(row.getLng()).isEqualByComparingTo("-1.2");
    }

    // --- Matview refresh: a freshly registered station is matched to its city
    // (by name, and by 25 km proximity) in station_cities. ---
    @Test
    void refreshesMatviewOnNameMatch() {
        long cityId = seedCity("Matchington", "Region-A", "50.000000", "0.000000");

        listener.onMessage(registrationXml(SERIAL, location("<city>Matchington</city>")));

        assertThat(isPaired(cityId)).isTrue();
    }

    @Test
    void refreshesMatviewOnGeoMatch() {
        // City and station carry no shared name; ~11 km apart, well inside 25 km.
        long cityId = seedCity("Faraway", "Region-B", "54.000000", "-1.000000");

        listener.onMessage(registrationXml(SERIAL, location(coordinates("54.100000", "-1.000000"))));

        assertThat(isPaired(cityId)).isTrue();
    }

    @Test
    void refreshesMatviewMatchingSeveralCitiesOnOverlap() {
        // Two cities ~5.5 km apart; the station sits between them, inside 25 km of
        // both, so it matches both -- overlap is intended (data-modelling iteration 3).
        long cityA = seedCity("Overlapton", "Region-C", "54.000000", "-1.000000");
        long cityB = seedCity("Overlingham", "Region-D", "54.050000", "-1.000000");

        listener.onMessage(registrationXml(SERIAL, location(coordinates("54.025000", "-1.000000"))));

        assertThat(isPaired(cityA)).isTrue();
        assertThat(isPaired(cityB)).isTrue();
    }

    // --- Lifecycle: a station's measurements are dropped as "unknown station"
    // until it registers, then accepted (iteration 3 skip-unknown-stations rule). ---
    @Test
    void acceptsMeasurementsOnlyAfterRegistration() {
        // Measures before registering -- unknown station, nothing stored.
        temperatureListener.onMessage(temperatureXml(SERIAL));
        assertThat(db.fetchCount(SCALAR_MEASUREMENTS)).isZero();

        listener.onMessage(registrationXml(SERIAL, location("<city>Testville</city>")));

        // Same measurement after registering -- now accepted.
        temperatureListener.onMessage(temperatureXml(SERIAL));
        assertThat(db.fetchCount(SCALAR_MEASUREMENTS)).isEqualTo(1);
    }

    // --- helpers ---

    private boolean isPaired(long cityId) {
        Long stationId = db.select(STATIONS.ID).from(STATIONS)
                .where(STATIONS.SERIAL_NO.eq(SERIAL)).fetchOne(STATIONS.ID);
        return db.fetchExists(db.selectFrom(STATION_CITIES)
                .where(STATION_CITIES.CITY_ID.eq(cityId).and(STATION_CITIES.STATION_ID.eq(stationId))));
    }

    private long seedCity(String cityName, String regionName, String lat, String lng) {
        long regionId = db.insertInto(REGIONS, REGIONS.NAME).values(regionName)
                .returningResult(REGIONS.ID).fetchOne().value1();
        return db.insertInto(CITIES, CITIES.NAME, CITIES.REGION_ID, CITIES.LAT, CITIES.LNG)
                .values(cityName, regionId, new BigDecimal(lat), new BigDecimal(lng))
                .returningResult(CITIES.ID).fetchOne().value1();
    }

    private void assertCoordinate(BigDecimal actual, String expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(expected);
        }
    }

    private static String coordinates(String lat, String lng) {
        return "<coordinates><latitude>" + lat + "</latitude><longitude>" + lng + "</longitude></coordinates>";
    }

    private static String location(String inner) {
        return "<location>" + inner + "</location>";
    }

    private static String temperatureXml(String serialNumber) {
        return """
                <station-measurements>
                    <serialNumber>%s</serialNumber>
                    <measurements>
                        <measurement>
                            <type>temperature</type>
                            <value>21.3</value>
                            <unit>°C</unit>
                            <timestamp>1752134400</timestamp>
                        </measurement>
                    </measurements>
                </station-measurements>
                """.formatted(serialNumber);
    }

    private static String registrationXml(String serialNumber, String locationXml) {
        String serialElement = serialNumber == null ? "" : "<serialNumber>" + serialNumber + "</serialNumber>";
        return """
                <station>
                    %s
                    %s
                </station>
                """.formatted(serialElement, locationXml);
    }
}
