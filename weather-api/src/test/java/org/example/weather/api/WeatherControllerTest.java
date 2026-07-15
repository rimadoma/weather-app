package org.example.weather.api;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.example.weather.db.generated.Tables.CITIES;
import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.db.generated.Tables.SCALAR_MEASUREMENTS;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.db.generated.Tables.STATION_CITIES;
import static org.example.weather.db.generated.Tables.WEATHER_WARNINGS;
import static org.example.weather.db.generated.Tables.WIND_MEASUREMENTS;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeatherControllerTest extends AbstractIntegrationTest {

    private static final AtomicLong SERIAL_NO_SEQ = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext db;

    private Long regionId;

    @BeforeEach
    void seedRegion() {
        // Needed because of FK constraint
        regionId = db.insertInto(REGIONS, REGIONS.NAME)
                .values("Testshire")
                .returningResult(REGIONS.ID)
                .fetchOne()
                .value1();
    }

    @Test
    void returnsAveragesForCityWithRecentMeasurements() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A measurement more than 1h in the past shouldn't count
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(18.0), now.minusMinutes(70));
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(20.0), now.minusMinutes(30));
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(22.0), now.minusMinutes(10));

        // Same 1h window; wind speed is a plain mean (2 and 4 -> 3.0), the old
        // reading (9.0) is outside it. Both live readings share direction 90, so
        // the mean is 90 regardless of speed weighting (that is exercised in its
        // own test); the old reading's 200 is excluded either way.
        insertWindMeasurement(stationId, BigDecimal.valueOf(9.0), (short) 200, now.minusMinutes(70));
        insertWindMeasurement(stationId, BigDecimal.valueOf(2.0), (short) 90, now.minusMinutes(30));
        insertWindMeasurement(stationId, BigDecimal.valueOf(4.0), (short) 90, now.minusMinutes(10));

        insertWarning("orange", "Flooding", now.minusHours(1), now.plusHours(1));

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.page").value(1))
                .andExpect(jsonPath("$.metadata.pageSize").value(25))
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].name").value("Ambridge"))
                .andExpect(jsonPath("$.cities[0].temperature").value(21.0))
                .andExpect(jsonPath("$.cities[0].windSpeed").value(3.0))
                .andExpect(jsonPath("$.cities[0].windDirection").value(90))
                .andExpect(jsonPath("$.cities[0].warnings", hasSize(1)))
                .andExpect(jsonPath("$.cities[0].warnings[0].severity").value("orange"))
                .andExpect(jsonPath("$.cities[0].warnings[0].description").value("Flooding"));
    }

    @Test
    void weightsWindDirectionMeanBySpeed() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A strong wind from 350* and a weak 10* one. Speed-weighted, the
        // mean is pulled toward the stronger reading
        insertWindMeasurement(stationId, BigDecimal.valueOf(25.0), (short) 350, now.minusMinutes(20));
        insertWindMeasurement(stationId, BigDecimal.valueOf(5.0), (short) 10, now.minusMinutes(10));

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].windDirection").value(353));
    }

    @Test
    void ordersCitiesAlphabeticallyWithTotalCount() throws Exception {
        insertCity("Zetown");
        insertCity("Ambridge");
        insertCity("Middleford");
        refreshStationCities();

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.totalCities").value(3))
                .andExpect(jsonPath("$.cities[*].name", contains("Ambridge", "Middleford", "Zetown")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-100", "abc"})
    void rejectsInvalidPage(String page) throws Exception {
        mockMvc.perform(get("/api/weather").param("page", page))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEmptyCitiesPastLastPageWithoutError() throws Exception {
        insertCity("Ambridge");

        // Total count still reported past the last page, via the fetchCount fallback
        mockMvc.perform(get("/api/weather").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities").isEmpty())
                .andExpect(jsonPath("$.metadata.totalCities").value(1));
    }

    @Test
    void noExplodeWhenNoCitiesExist() throws Exception {
        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities").isEmpty());
    }

    @Test
    void returnsNullReadingsWhenCityHasNoStations() throws Exception {
        Long cityId = insertCity("Ambridge");
        refreshStationCities();

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].temperature").value(nullValue()))
                .andExpect(jsonPath("$.cities[0].windSpeed").value(nullValue()))
                .andExpect(jsonPath("$.cities[0].windDirection").value(nullValue()));
    }

    @Test
    void returnsNullWindWhenCityHasTemperatureButNoWind() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Temperature present, no wind rows at all: the wind pair nulls out
        // together (iteration 17), independently of temperature being there.
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(19.0), now.minusMinutes(10));

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].temperature").value(19.0))
                .andExpect(jsonPath("$.cities[0].windSpeed").value(nullValue()))
                .andExpect(jsonPath("$.cities[0].windDirection").value(nullValue()));
    }

    @Test
    void noExplodeWhenStationHasNoMeasurements() throws Exception {
        Long cityId = insertCity("Ambridge");
        insertStation("Ambridge");
        refreshStationCities();

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].temperature").value(nullValue()))
                .andExpect(jsonPath("$.cities[0].windSpeed").value(nullValue()))
                .andExpect(jsonPath("$.cities[0].windDirection").value(nullValue()));
    }

    @Test
    void returns500WhenMaterialisedViewNotYetRefreshed() throws Exception {
        insertCity("Ambridge");
        insertStation("Ambridge");
        // Deliberately not refreshed: station_cities is created WITH NO DATA
        // and stays unpopulated until a REFRESH runs -- querying it as-is is
        // a genuine bug/broken precondition, not a "no data" case.

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void warningsDefaultToEmptyListWhenCityHasNone() throws Exception {
        insertCity("Ambridge");
        refreshStationCities();

        // Explicitly an empty array, never null -- isArray() fails on null.
        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].warnings").isArray())
                .andExpect(jsonPath("$.cities[0].warnings", hasSize(0)));
    }

    @Test
    void returnsAllActiveWarningsForCityAndOmitsExpired() throws Exception {
        insertCity("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertWarning("yellow", "High winds", now.minusHours(2), now.plusHours(2));
        insertWarning("red", "Flooding", now.minusHours(1), now.plusHours(6));
        insertWarning("orange", "Ice", now.minusHours(5), now.minusHours(1)); // expired

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].warnings", hasSize(2)));
    }

    // The controller computes its own `now`, always >= the `now` seeded here
    // (time only moves forward). That keeps the two boundary cases deterministic:
    // a start == seed-now is always <= the controller's now (returned), and an
    // end == seed-now is never > the controller's now (excluded -- end exclusive).
    @ParameterizedTest(name = "{0}")
    @MethodSource("warningWindows")
    void filtersWarningsByActiveWindow(String label, long startOffsetSeconds, long endOffsetSeconds, boolean active)
            throws Exception {
        insertCity("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertWarning("yellow", "High winds",
                now.plusSeconds(startOffsetSeconds), now.plusSeconds(endOffsetSeconds));

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].warnings", hasSize(active ? 1 : 0)));
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

    private void insertWarning(String severity, String description, OffsetDateTime start, OffsetDateTime end) {
        db.insertInto(WEATHER_WARNINGS, WEATHER_WARNINGS.REGION_ID, WEATHER_WARNINGS.SEVERITY,
                        WEATHER_WARNINGS.DESCRIPTION, WEATHER_WARNINGS.START_TIME, WEATHER_WARNINGS.END_TIME)
                .values(regionId, severity, description, start, end)
                .execute();
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
