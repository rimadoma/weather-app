package org.example.weather.materialiser.listeners;

import org.example.weather.db.generated.tables.records.WeatherWarningsRecord;
import org.example.weather.materialiser.AbstractIntegrationTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.db.generated.Tables.WEATHER_WARNINGS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
@Transactional
class WarningListenerTest extends AbstractIntegrationTest {

    private static final String REGION = "Testshire";
    private static final String DESCRIPTION = "Flooding";
    private static final long START_EPOCH = 1752134400L;
    private static final long END_EPOCH = 1752307200L; // START + 48 h
    private static final String START = String.valueOf(START_EPOCH);
    private static final String END = String.valueOf(END_EPOCH);

    @Autowired
    private WarningListener listener;

    @Autowired
    private DSLContext db;

    private long regionId;

    @BeforeEach
    void seedRegion() {
        regionId = db.insertInto(REGIONS, REGIONS.NAME).values(REGION)
                .returningResult(REGIONS.ID).fetchOne().value1();
    }

    // --- Happy path: a valid warning is stored, severity normalised to lower case. ---
    @ParameterizedTest(name = "inserts a warning with severity {0} stored as {1}")
    @CsvSource({"Yellow,yellow", "Orange,orange", "Red,red"})
    void insertsWarning(String inputSeverity, String storedSeverity) {
        listener.onMessage(warningXml(REGION, inputSeverity, DESCRIPTION, START, END));

        WeatherWarningsRecord row = db.selectFrom(WEATHER_WARNINGS).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getRegionId()).isEqualTo(regionId);
        assertThat(row.getSeverity()).isEqualTo(storedSeverity);
        assertThat(row.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(row.getStartTime().toInstant()).isEqualTo(Instant.ofEpochSecond(START_EPOCH));
        assertThat(row.getEndTime().toInstant()).isEqualTo(Instant.ofEpochSecond(END_EPOCH));
    }

    // --- Skips: bad or unusable input, or input that would otherwise trip a DB
    // CHECK. The listener must validate and bail cleanly -- no row, no throw. ---
    @ParameterizedTest
    @MethodSource("invalidWarnings")
    void skipsInvalidWarning(String xml) {
        assertDoesNotThrow(() -> listener.onMessage(xml));
        assertThat(db.fetchCount(WEATHER_WARNINGS)).isZero();
    }

    private static Stream<String> invalidWarnings() {
        return Stream.of(
                // Malformed XML
                "<weather_warning><region>" + REGION,
                // Bad region
                warningXml("Nowhereshire", "Yellow", DESCRIPTION, START, END),
                warningXml(null, "Yellow", DESCRIPTION, START, END),
                warningXml("", "Yellow", DESCRIPTION, START, END),
                // Bad severity
                warningXml(REGION, "Purple", DESCRIPTION, START, END),
                warningXml(REGION, null, DESCRIPTION, START, END),
                warningXml(REGION, "", DESCRIPTION, START, END),
                // Bad description
                warningXml(REGION, "Yellow", null, START, END),
                warningXml(REGION, "Yellow", "", START, END),
                // Bad timestamps
                warningXml(REGION, "Yellow", DESCRIPTION, END, START), // end before start
                warningXml(REGION, "Yellow", DESCRIPTION, START, START) // end equal to start
        );
    }

    // --- helpers ---

    // A null argument omits the element entirely; "" renders a blank element.
    private static String warningXml(String region, String severity, String description, String start, String end) {
        StringBuilder xml = new StringBuilder("<weather_warning>");
        appendElement(xml, "region", region);
        appendElement(xml, "severity", severity);
        appendElement(xml, "description", description);
        appendElement(xml, "start_time", start);
        appendElement(xml, "end_time", end);
        return xml.append("</weather_warning>").toString();
    }

    private static void appendElement(StringBuilder xml, String name, String value) {
        if (value != null) {
            xml.append('<').append(name).append('>').append(value).append("</").append(name).append('>');
        }
    }
}
