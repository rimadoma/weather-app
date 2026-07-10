package org.example.weather.api;

import org.example.weather.api.generated.api.CitiesApi;
import org.example.weather.api.generated.model.CityListResponse;
import org.example.weather.api.generated.model.CityRef;
import org.example.weather.api.generated.model.PageMetadata;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.example.weather.api.Constants.PAGE_SIZE;
import static org.example.weather.api.Constants.TOTAL_COUNT;
import static org.example.weather.db.generated.Tables.CITIES;
import static org.jooq.impl.DSL.count;

@RestController
public class CitiesController implements CitiesApi {


    private final DSLContext db;

    public CitiesController(DSLContext db) {
        this.db = db;
    }

    @Override
    public ResponseEntity<CityListResponse> getCities(Integer page) {
        int offset = (page - 1) * PAGE_SIZE;
        Result<Record3<Long, String, Integer>> cities = db.select(CITIES.ID, CITIES.NAME, count().over().as(TOTAL_COUNT))
                .from(CITIES)
                .orderBy(CITIES.NAME)
                .offset(offset)
                .limit(PAGE_SIZE)
                .fetch();

        int totalCount = cities.isNotEmpty() ? cities.getFirst().value3() : db.fetchCount(CITIES);
        List<CityRef> cityRefs = cities.stream()
                .map(record -> new CityRef(record.value1(), record.value2()))
                .toList();
        PageMetadata metadata = new PageMetadata(page, PAGE_SIZE, totalCount);
        CityListResponse response = new CityListResponse(metadata, cityRefs);

        return ResponseEntity.ok(response);
    }
}
