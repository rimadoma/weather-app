package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.WindMeasurementsRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.example.weather.materialiser.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.db.generated.tables.WindMeasurements.WIND_MEASUREMENTS;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@Transactional
class WindListenerTest extends AbstractIntegrationTest {

    private static final String KNOWN_SERIAL = "ST-001";
    private static final long TS = 1752134400L;

    @Autowired
    private WindListener listener;

    @Autowired
    private DSLContext db;

    private Long stationId;

    @BeforeEach
    void seedStation() {
        stationId = db.insertInto(STATIONS, STATIONS.SERIAL_NO, STATIONS.CITY_NAME)
                .values(KNOWN_SERIAL, "Testopolis")
                .returningResult(STATIONS.ID)
                .fetchOne()
                .value1();
    }

    @Test
    void pairsSpeedAndDirectionIntoOneRow() {
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "5.0", "m/s", TS),
                entry("wind_direction", "180", "degrees", TS)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSpeed()).isEqualByComparingTo("5.0");
        assertThat(rows.getFirst().getDirection()).isEqualTo((short) 180);
    }

    @Test
    void normalisesMphToMetresPerSecond() {
        // 22.369362920544 mph == 10 m/s
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "22.369362920544", "mph", TS),
                entry("wind_direction", "90", "degrees", TS)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSpeed()).isEqualByComparingTo("10.0");
    }

    @Test
    void writesZeroSpeedReading() {
        // Calm wind is real data, and CHECK (speed >= 0) allows it.
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "0.0", "m/s", TS),
                entry("wind_direction", "90", "degrees", TS)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSpeed()).isEqualByComparingTo("0.0");
    }

    @Test
    void convertsEpochSecondsToUtcTimestamp() {
        OffsetDateTime measuredAt = OffsetDateTime.of(2025, 7, 10, 8, 0, 0, 0, ZoneOffset.UTC);

        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "3.0", "m/s", measuredAt.toEpochSecond()),
                entry("wind_direction", "0", "degrees", measuredAt.toEpochSecond())));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getMeasuredAt()).isEqualTo(measuredAt);
    }

    @Test
    void writesEachCompletePairFromAMultiPairMessage() {
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "5.0", "m/s", TS),
                entry("wind_direction", "180", "degrees", TS),
                entry("wind_speed", "6.0", "m/s", TS + 900),
                entry("wind_direction", "200", "degrees", TS + 900)));

        assertThat(windForStation()).hasSize(2);
    }

    @Test
    void keepsOnlyCompletePairsFromAMixedMessage() {
        // One full pair at TS, a lone speed at TS+900 -- only the pair lands,
        // proving the skip is per-timestamp, not per-message.
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "5.0", "m/s", TS),
                entry("wind_direction", "180", "degrees", TS),
                entry("wind_speed", "6.0", "m/s", TS + 900)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getDirection()).isEqualTo((short) 180);
    }

    @Test
    void keepsLastEntryForDuplicateTimestamp() {
        // Two speeds share a timestamp -- the later one wins.
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "5.0", "m/s", TS),
                entry("wind_speed", "6.0", "m/s", TS),
                entry("wind_direction", "180", "degrees", TS)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSpeed()).isEqualByComparingTo("6.0");
    }

    @Test
    void bestEffortBatchSkipsBadPairsAndWritesGoodOnes() {
        // A batch of pairs, each bad in a different way, plus two clean ones.
        // Only the clean pairs (directions 90 and 270) should survive.
        listener.onMessage(message(KNOWN_SERIAL,
                entry("wind_speed", "not-a-number", "m/s", TS),        // unparseable speed
                entry("wind_direction", "180", "degrees", TS),
                entry("wind_speed", "5.0", "km/h", TS + 900),          // unknown speed unit
                entry("wind_direction", "200", "degrees", TS + 900),
                entry("wind_speed", "6.0", "m/s", TS + 1800),          // non-integer direction
                entry("wind_direction", "18.5", "degrees", TS + 1800),
                entry("wind_speed", "7.0", "m/s", TS + 2700),          // out-of-range direction
                entry("wind_direction", "400", "degrees", TS + 2700),
                entry("wind_speed", "-3.0", "m/s", TS + 3600),         // negative speed
                entry("wind_direction", "45", "degrees", TS + 3600),
                entry("wind_speed", "8.0", "m/s", TS + 4500),          // negative direction
                entry("wind_direction", "-10", "degrees", TS + 4500),
                entry("wind_speed", "4.0", "m/s", TS + 5100),          // unknown direction unit
                entry("wind_direction", "100", "radians", TS + 5100),
                entry("wind_speed", "8.0", "m/s", TS + 5400),          // clean
                entry("wind_direction", "90", "degrees", TS + 5400),
                entry("wind_speed", "9.0", "m/s", TS + 6300),          // clean
                entry("wind_direction", "270", "degrees", TS + 6300)));

        List<WindMeasurementsRecord> rows = windForStation();
        assertThat(rows).extracting(WindMeasurementsRecord::getDirection)
                .containsExactlyInAnyOrder((short) 90, (short) 270);
    }

    @Test
    void doesNotExplodeOnMalformedXml() {
        listener.onMessage("<station-measurements><serialNumber>" + KNOWN_SERIAL);

        assertThat(db.fetchCount(WIND_MEASUREMENTS)).isZero();
    }

    @Test
    void noopsWhenNoWindMeasurementsPresent() {
        listener.onMessage(message(KNOWN_SERIAL, entry("temperature", "21.3", "°C", TS)));

        assertThat(db.fetchCount(WIND_MEASUREMENTS)).isZero();
    }

    @Test
    void noopsForUnknownStation() {
        listener.onMessage(message("ST-UNKNOWN",
                entry("wind_speed", "5.0", "m/s", TS),
                entry("wind_direction", "180", "degrees", TS)));

        assertThat(db.fetchCount(WIND_MEASUREMENTS)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("partialMessages")
    void doesNotWritePartialData(String description, String xml) {
        listener.onMessage(xml);

        assertThat(db.fetchCount(WIND_MEASUREMENTS)).isZero();
    }

    static Stream<Arguments> partialMessages() {
        return Stream.of(
                arguments("speed without direction",
                        message(KNOWN_SERIAL, entry("wind_speed", "5.0", "m/s", TS))),
                arguments("direction without speed",
                        message(KNOWN_SERIAL, entry("wind_direction", "180", "degrees", TS))),
                arguments("both present but different timestamps",
                        message(KNOWN_SERIAL,
                                entry("wind_speed", "5.0", "m/s", TS),
                                entry("wind_direction", "180", "degrees", TS + 1))));
    }

    private List<WindMeasurementsRecord> windForStation() {
        return db.selectFrom(WIND_MEASUREMENTS)
                .where(WIND_MEASUREMENTS.STATION_ID.eq(stationId))
                .fetch();
    }

    private static String message(String serialNumber, String... measurementEntries) {
        return """
                <station-measurements>
                    <serialNumber>%s</serialNumber>
                    <measurements>%s
                    </measurements>
                </station-measurements>
                """.formatted(serialNumber, String.join("", measurementEntries));
    }

    private static String entry(String type, String value, String unit, long timestamp) {
        return """

                        <measurement>
                            <type>%s</type>
                            <value>%s</value>
                            <unit>%s</unit>
                            <timestamp>%d</timestamp>
                        </measurement>""".formatted(type, value, unit, timestamp);
    }
}
