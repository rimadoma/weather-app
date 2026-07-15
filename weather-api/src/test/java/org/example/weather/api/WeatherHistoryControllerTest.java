package org.example.weather.api;

import org.example.weather.api.generated.model.WeatherBucket;
import org.example.weather.api.generated.model.WeatherHistoryResponse;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.weather.db.generated.Tables.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeatherHistoryControllerTest extends AbstractIntegrationTest {

    private static final AtomicLong SERIAL_NO_SEQ = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DSLContext db;

    private Long regionId;

    @BeforeEach
    void seedRegion() {
        regionId = db.insertInto(REGIONS, REGIONS.NAME)
                .values("Testshire")
                .returningResult(REGIONS.ID)
                .fetchOne()
                .value1();
    }

    @Test
    void returnsSixHourBucketGridForCity() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime measuredAt = now.minusHours(7);
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(18.0), measuredAt);
        insertWindMeasurement(stationId, BigDecimal.valueOf(3.0), (short) 90, measuredAt);

        // An active warning for the city's region is attached to the response.
        insertWarning("orange", "Flooding", now.minusHours(1), now.plusHours(1));

        WeatherHistoryResponse body = getHistory(cityId);

        assertThat(body.getId()).isEqualTo(cityId);
        assertThat(body.getName()).isEqualTo("Ambridge");
        assertThat(body.getWarnings()).hasSize(1);
        assertThat(body.getWarnings().getFirst().getSeverity()).isEqualTo("orange");
        assertThat(body.getWarnings().getFirst().getDescription()).isEqualTo("Flooding");

        List<WeatherBucket> buckets = body.getBuckets();
        assertGridShape(buckets);

        // The reading lands in exactly the bucket covering its timestamp; the rest are null.
        WeatherBucket target = bucketContaining(buckets, measuredAt);
        assertThat(target.getTemperature()).isEqualTo(18.0);
        assertThat(target.getWindSpeed()).isEqualTo(3.0);
        assertThat(target.getWindDirection()).isEqualTo(90);
        assertThat(buckets).filteredOn(b -> b != target)
                .allMatch(b -> b.getTemperature() == null && b.getWindSpeed() == null && b.getWindDirection() == null);
    }

    @Test
    void returns404ForUnknownCity() throws Exception {
        mockMvc.perform(get("/api/weather/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsFullNullGridWhenCityHasNoMeasurements() throws Exception {
        Long cityId = insertCity("Ambridge");
        insertStation("Ambridge");
        refreshStationCities();

        WeatherHistoryResponse body = getHistory(cityId);

        // Missing data is null, never omitted (iteration 5 / 21): the full grid is
        // present, every bucket carrying null readings -- not an empty bucket array.
        List<WeatherBucket> buckets = body.getBuckets();
        assertGridShape(buckets);
        assertThat(buckets).allMatch(b ->
                b.getTemperature() == null && b.getWindSpeed() == null && b.getWindDirection() == null);
    }

    @Test
    void placesMeasurementsInTheirOwnBuckets() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime early = now.minusDays(3);
        OffsetDateTime late = now.minusDays(1);
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(5.0), early);
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(15.0), late);

        List<WeatherBucket> buckets = getHistory(cityId).getBuckets();

        assertThat(bucketContaining(buckets, early).getTemperature()).isEqualTo(5.0);
        assertThat(bucketContaining(buckets, late).getTemperature()).isEqualTo(15.0);
        // Two readings in two distinct buckets.
        assertThat(buckets).filteredOn(b -> b.getTemperature() != null).hasSize(2);
    }

    @Test
    void excludesMeasurementsOlderThanTheWindow() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Comfortably before window_start (current bucket - 6 days).
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(9.0), now.minusDays(10));

        List<WeatherBucket> buckets = getHistory(cityId).getBuckets();

        assertGridShape(buckets);
        assertThat(buckets).allMatch(b -> b.getTemperature() == null);
    }

    @Test
    void bucketWindIsBothOrNeither() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime windAt = now.minusDays(1);
        OffsetDateTime tempAt = now.minusDays(3);
        insertWindMeasurement(stationId, BigDecimal.valueOf(4.0), (short) 180, windAt);
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(12.0), tempAt);

        List<WeatherBucket> buckets = getHistory(cityId).getBuckets();

        WeatherBucket windBucket = bucketContaining(buckets, windAt);
        assertThat(windBucket.getWindSpeed()).isEqualTo(4.0);
        assertThat(windBucket.getWindDirection()).isEqualTo(180);

        WeatherBucket tempBucket = bucketContaining(buckets, tempAt);
        assertThat(tempBucket.getTemperature()).isEqualTo(12.0);
        assertThat(tempBucket.getWindSpeed()).isNull();
        assertThat(tempBucket.getWindDirection()).isNull();
    }

    @Test
    void returns500WhenMaterialisedViewNotYetRefreshed() throws Exception {
        Long cityId = insertCity("Ambridge");
        insertStation("Ambridge");
        // Deliberately not refreshed: the city exists (so we get past the 404 check),
        // but joining the unpopulated station_cities is a broken precondition that
        // should bubble up as a 500, not degrade.

        mockMvc.perform(get("/api/weather/{id}", cityId))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void averagesTemperaturesWithinABucket() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime bucket = Helpers.bucketStarts(OffsetDateTime.now(ZoneOffset.UTC)).get(3);
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(10.0), bucket.plusHours(1));
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(20.0), bucket.plusHours(2));

        WeatherBucket target = bucketContaining(getHistory(cityId).getBuckets(), bucket.plusHours(1));
        assertThat(target.getTemperature()).isEqualTo(15.0);
    }

    @Test
    void weightsWindDirectionBySpeedWithinABucket() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime bucket = Helpers.bucketStarts(OffsetDateTime.now(ZoneOffset.UTC)).get(3);
        // Strong wind from 350 and a weak one from 10, same bucket: the speed-weighted
        // mean leans toward the stronger reading (mirrors the list endpoint).
        insertWindMeasurement(stationId, BigDecimal.valueOf(25.0), (short) 350, bucket.plusHours(1));
        insertWindMeasurement(stationId, BigDecimal.valueOf(5.0), (short) 10, bucket.plusHours(2));

        WeatherBucket target = bucketContaining(getHistory(cityId).getBuckets(), bucket.plusHours(1));
        assertThat(target.getWindSpeed()).isEqualTo(15.0); // plain mean of 25 and 5
        assertThat(target.getWindDirection()).isEqualTo(353);
    }

    @Test
    void aggregatesMeasurementsFromAllStationsOfACity() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationA = insertStation("Ambridge");
        Long stationB = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime bucket = Helpers.bucketStarts(OffsetDateTime.now(ZoneOffset.UTC)).get(3);
        // Two stations of the same city, same bucket: averaged together via the matview join.
        insertTemperatureMeasurement(stationA, BigDecimal.valueOf(10.0), bucket.plusHours(1));
        insertTemperatureMeasurement(stationB, BigDecimal.valueOf(20.0), bucket.plusHours(2));

        WeatherBucket target = bucketContaining(getHistory(cityId).getBuckets(), bucket.plusHours(1));
        assertThat(target.getTemperature()).isEqualTo(15.0);
    }

    @Test
    void measurementOnBucketBoundaryLandsInThatBucket() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime boundary = Helpers.bucketStarts(OffsetDateTime.now(ZoneOffset.UTC)).get(3);
        // Exactly on the boundary belongs to the bucket that starts there
        // (lower-inclusive), not the previous one.
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(7.0), boundary);

        List<WeatherBucket> buckets = getHistory(cityId).getBuckets();
        assertThat(bucketContaining(buckets, boundary).getTemperature()).isEqualTo(7.0);
        assertThat(bucketContaining(buckets, boundary.minusSeconds(1)).getTemperature()).isNull();
    }

    @Test
    void warningsDefaultToEmptyListWhenCityHasNone() throws Exception {
        Long cityId = insertCity("Ambridge");
        refreshStationCities();

        // Present and empty, never null.
        assertThat(getHistory(cityId).getWarnings()).isNotNull().isEmpty();
    }

    @Test
    void returnsAllActiveWarningsForCityAndOmitsExpired() throws Exception {
        Long cityId = insertCity("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertWarning("yellow", "High winds", now.minusHours(2), now.plusHours(2));
        insertWarning("red", "Flooding", now.minusHours(1), now.plusHours(6));
        insertWarning("orange", "Ice", now.minusHours(5), now.minusHours(1)); // expired

        assertThat(getHistory(cityId).getWarnings()).hasSize(2);
    }

    // The controller computes its own `now`, always >= the `now` seeded here
    // (time only moves forward). That keeps the two boundary cases deterministic:
    // a start == seed-now is always <= the controller's now (returned), and an
    // end == seed-now is never > the controller's now (excluded -- end exclusive).
    @ParameterizedTest(name = "{0}")
    @MethodSource("warningWindows")
    void filtersWarningsByActiveWindow(String label, long startOffsetSeconds, long endOffsetSeconds, boolean active)
            throws Exception {
        Long cityId = insertCity("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertWarning("yellow", "High winds",
                now.plusSeconds(startOffsetSeconds), now.plusSeconds(endOffsetSeconds));

        assertThat(getHistory(cityId).getWarnings()).hasSize(active ? 1 : 0);
    }

    private static Stream<Arguments> warningWindows() {
        return Stream.of(
                Arguments.of("active: started in the past, ends in the future", -3600L, 3600L, true),
                Arguments.of("active: starts now, ends in the future", 0L, 3600L, true),
                Arguments.of("inactive: starts in the future", 3600L, 7200L, false),
                Arguments.of("expired: ended in the past", -7200L, -3600L, false),
                Arguments.of("boundary: ends now, end is exclusive", -3600L, 0L, false)
        );
    }

    // --- helpers ---

    private void insertWarning(String severity, String description, OffsetDateTime start, OffsetDateTime end) {
        db.insertInto(WEATHER_WARNINGS, WEATHER_WARNINGS.REGION_ID, WEATHER_WARNINGS.SEVERITY,
                        WEATHER_WARNINGS.DESCRIPTION, WEATHER_WARNINGS.START_TIME, WEATHER_WARNINGS.END_TIME)
                .values(regionId, severity, description, start, end)
                .execute();
    }

    private WeatherHistoryResponse getHistory(long cityId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/weather/{id}", cityId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), WeatherHistoryResponse.class);
    }

    /**
     * Grid shape invariant of the wall-clock: 25 buckets aligned to 02/08/14/20 UTC, 6 h apart, spanning 6 days, ending at the current partial bucket.
     */
    private void assertGridShape(List<WeatherBucket> buckets) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(buckets).hasSize(25);

        for (WeatherBucket b : buckets) {
            OffsetDateTime start = b.getStartTime().withOffsetSameInstant(ZoneOffset.UTC);
            assertThat(start.getMinute()).isZero();
            assertThat(start.getSecond()).isZero();
            assertThat(Math.floorMod(start.getHour(), 6)).isEqualTo(2); // 02/08/14/20
        }
        for (int i = 1; i < buckets.size(); i++) {
            assertThat(buckets.get(i).getStartTime().isEqual(buckets.get(i - 1).getStartTime().plusHours(6))).isTrue();
        }
        OffsetDateTime first = buckets.getFirst().getStartTime();
        OffsetDateTime last = buckets.getLast().getStartTime();
        assertThat(first.isEqual(last.minusHours(24 * 6))).isTrue(); // window_start = current bucket - 6 days
        assertThat(last.isAfter(now)).isFalse();
        assertThat(last.plusHours(6).isAfter(now)).isTrue(); // last is the current, partial bucket
    }

    private static WeatherBucket bucketContaining(List<WeatherBucket> buckets, OffsetDateTime t) {
        return buckets.stream()
                .filter(b -> !t.isBefore(b.getStartTime()) && t.isBefore(b.getStartTime().plusHours(6)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No bucket covers " + t));
    }

    private Long insertCity(String name) {
        return db.insertInto(CITIES, CITIES.NAME, CITIES.REGION_ID, CITIES.LAT, CITIES.LNG)
                .values(name, regionId, BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1))
                .returningResult(CITIES.ID)
                .fetchOne()
                .value1();
    }

    private Long insertStation(String cityName) {
        return db.insertInto(STATIONS, STATIONS.SERIAL_NO, STATIONS.CITY_NAME)
                .values("TEST-" + SERIAL_NO_SEQ.incrementAndGet(), cityName)
                .returningResult(STATIONS.ID)
                .fetchOne()
                .value1();
    }

    private void insertTemperatureMeasurement(Long stationId, BigDecimal reading, OffsetDateTime measuredAt) {
        db.insertInto(SCALAR_MEASUREMENTS, SCALAR_MEASUREMENTS.STATION_ID, SCALAR_MEASUREMENTS.TYPE,
                        SCALAR_MEASUREMENTS.READING, SCALAR_MEASUREMENTS.MEASURED_AT)
                .values(stationId, "temperature", reading, measuredAt)
                .execute();
    }

    private void insertWindMeasurement(Long stationId, BigDecimal speed, short direction, OffsetDateTime measuredAt) {
        db.insertInto(WIND_MEASUREMENTS, WIND_MEASUREMENTS.STATION_ID, WIND_MEASUREMENTS.SPEED,
                        WIND_MEASUREMENTS.DIRECTION, WIND_MEASUREMENTS.MEASURED_AT)
                .values(stationId, speed, direction, measuredAt)
                .execute();
    }

    private void refreshStationCities() {
        db.execute("REFRESH MATERIALIZED VIEW " + STATION_CITIES.getName());
    }
}
