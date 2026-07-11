package org.example.weather.api;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import static org.example.weather.db.generated.Tables.CITIES;
import static org.example.weather.db.generated.Tables.REGIONS;
import static org.example.weather.db.generated.Tables.SCALAR_MEASUREMENTS;
import static org.example.weather.db.generated.Tables.STATIONS;
import static org.example.weather.db.generated.Tables.STATION_CITIES;
import static org.hamcrest.Matchers.contains;
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
    void returnsAverageTemperatureForCityWithRecentMeasurements() throws Exception {
        Long cityId = insertCity("Ambridge");
        Long stationId = insertStation("Ambridge");
        refreshStationCities();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A measurement more than 1h in the past shouldn't count
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(18.0), now.minusMinutes(70));
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(20.0), now.minusMinutes(30));
        insertTemperatureMeasurement(stationId, BigDecimal.valueOf(22.0), now.minusMinutes(10));

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.page").value(1))
                .andExpect(jsonPath("$.metadata.pageSize").value(25))
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].name").value("Ambridge"))
                .andExpect(jsonPath("$.cities[0].temperature").value(21.0))
                .andExpect(jsonPath("$.cities[0].warnings").isEmpty());
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
    void returnsNullTemperatureWhenCityHasNoStations() throws Exception {
        Long cityId = insertCity("Ambridge");
        refreshStationCities();

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].temperature").value(nullValue()));
    }

    @Test
    void noExplodeWhenStationHasNoMeasurements() throws Exception {
        Long cityId = insertCity("Ambridge");
        insertStation("Ambridge");
        refreshStationCities();

        mockMvc.perform(get("/api/weather").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities[0].id").value(cityId))
                .andExpect(jsonPath("$.cities[0].temperature").value(nullValue()));
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

    private void refreshStationCities() {
        db.execute("REFRESH MATERIALIZED VIEW " + STATION_CITIES.getName());
    }
}
