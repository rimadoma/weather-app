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

import static org.example.weather.db.generated.Tables.CITIES;
import static org.example.weather.db.generated.Tables.REGIONS;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CitiesControllerTest extends AbstractIntegrationTest {

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
    void returnsCitiesAlphabeticallyWithTotalCount() throws Exception {
        insertCity("Zetown");
        insertCity("Ambridge");
        insertCity("Middleford");

        mockMvc.perform(get("/api/cities").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.page").value(1))
                .andExpect(jsonPath("$.metadata.pageSize").value(25))
                .andExpect(jsonPath("$.metadata.totalCities").value(3))
                .andExpect(jsonPath("$.cities[*].name", contains("Ambridge", "Middleford", "Zetown")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-100", "abc"})
    void rejectsInvalidPage(String page) throws Exception {
        mockMvc.perform(get("/api/cities").param("page", page))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEmptyCitiesPastLastPageWithoutError() throws Exception {
        insertCity("Ambridge");
        insertCity("Middleford");
        insertCity("Zetown");

        mockMvc.perform(get("/api/cities").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cities").isEmpty())
                .andExpect(jsonPath("$.metadata.totalCities").value(3));
    }

    private void insertCity(String name) {
        db.insertInto(CITIES, CITIES.NAME, CITIES.REGION_ID, CITIES.LAT, CITIES.LNG)
                .values(name, regionId, BigDecimal.valueOf(51.5), BigDecimal.valueOf(-0.1))
                .execute();
    }
}
