package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.ScalarMeasurementsRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.example.weather.materialiser.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.db.generated.tables.ScalarMeasurements.SCALAR_MEASUREMENTS;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@Transactional
class TemperatureListenerTest extends AbstractIntegrationTest {

    private static final String KNOWN_SERIAL = "ST-001";

    @Autowired
    private TemperatureListener listener;

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
    void insertsCelsiusReadingAsIs() {
        listener.onMessage(measurementXml(KNOWN_SERIAL, "temperature", "21.3", "°C", 1752134400L));

        List<ScalarMeasurementsRecord> rows = readingsForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getReading()).isEqualByComparingTo("21.3");
    }

    @Test
    void normalisesFahrenheitToCelsius() {
        listener.onMessage(measurementXml(KNOWN_SERIAL, "temperature", "98.6", "°F", 1752134400L));

        List<ScalarMeasurementsRecord> rows = readingsForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getReading()).isEqualByComparingTo("37.0");
    }

    @Test
    void convertsEpochSecondsToUtcTimestamp() {
        OffsetDateTime measuredAt = OffsetDateTime.of(2025, 7, 10, 8, 0, 0, 0, ZoneOffset.UTC);

        listener.onMessage(measurementXml(KNOWN_SERIAL, "temperature", "10.0", "°C", measuredAt.toEpochSecond()));

        List<ScalarMeasurementsRecord> rows = readingsForStation();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getMeasuredAt()).isEqualTo(measuredAt);
    }

    @Test
    void doesNotExplodeOnMalformedXml() {
        listener.onMessage("<station-measurements><serialNumber>" + KNOWN_SERIAL);

        assertThat(db.fetchCount(SCALAR_MEASUREMENTS)).isZero();
    }

    @Test
    void noopsWhenNoTemperatureMeasurementsPresent() {
        listener.onMessage(measurementXml(KNOWN_SERIAL, "humidity", "55", "%", 1752134400L));

        assertThat(db.fetchCount(SCALAR_MEASUREMENTS)).isZero();
    }

    @Test
    void noopsForUnknownStation() {
        listener.onMessage(measurementXml("ST-UNKNOWN", "temperature", "20.0", "°C", 1752134400L));

        assertThat(db.fetchCount(SCALAR_MEASUREMENTS)).isZero();
    }

    @Test
    void keepsOnlyValidReadingsFromAMixedMessage() {
        String xml = """
                <station-measurements>
                    <serialNumber>%s</serialNumber>
                    <measurements>
                        <measurement>
                            <type>temperature</type>
                            <value>10.0</value>
                            <unit>°C</unit>
                            <timestamp>1752134400</timestamp>
                        </measurement>
                        <measurement>
                            <type>temperature</type>
                            <value>98.6</value>
                            <unit>°F</unit>
                            <timestamp>1752134401</timestamp>
                        </measurement>
                        <measurement>
                            <type>temperature</type>
                            <value>not-a-number</value>
                            <unit>°C</unit>
                            <timestamp>1752134402</timestamp>
                        </measurement>
                        <measurement>
                            <type>temperature</type>
                            <value>15.0</value>
                            <unit>°K</unit>
                            <timestamp>1752134403</timestamp>
                        </measurement>
                        <measurement>
                            <type>humidity</type>
                            <value>50</value>
                            <unit>%%</unit>
                            <timestamp>1752134404</timestamp>
                        </measurement>
                    </measurements>
                </station-measurements>
                """.formatted(KNOWN_SERIAL);

        listener.onMessage(xml);

        List<ScalarMeasurementsRecord> rows = readingsForStation();
        assertThat(rows).extracting(r -> r.getReading().stripTrailingZeros())
                .containsExactlyInAnyOrder(new BigDecimal("10.0").stripTrailingZeros(), new BigDecimal("37.0").stripTrailingZeros());
    }

    private List<ScalarMeasurementsRecord> readingsForStation() {
        return db.selectFrom(SCALAR_MEASUREMENTS)
                .where(SCALAR_MEASUREMENTS.STATION_ID.eq(stationId))
                .fetch();
    }

    private static String measurementXml(String serialNumber, String type, String value, String unit, long timestamp) {
        return """
                <station-measurements>
                    <serialNumber>%s</serialNumber>
                    <measurements>
                        <measurement>
                            <type>%s</type>
                            <value>%s</value>
                            <unit>%s</unit>
                            <timestamp>%d</timestamp>
                        </measurement>
                    </measurements>
                </station-measurements>
                """.formatted(serialNumber, type, value, unit, timestamp);
    }
}
