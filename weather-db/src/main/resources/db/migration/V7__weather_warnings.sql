-- Schema per docs/data-modelling.md, iteration 1 (weather_warnings).
-- severity follows the iteration 6 precedent (scalar_measurements.type):
-- VARCHAR + CHECK rather than a Postgres ENUM, so a future severity is a
-- plain, fully transactional ALTER ... DROP/ADD CONSTRAINT under Flyway.
CREATE TABLE weather_warnings
(
    id          BIGSERIAL PRIMARY KEY,
    region_id   BIGINT       NOT NULL,
    description VARCHAR(100) NOT NULL,
    severity    VARCHAR(20)  NOT NULL,
    start_time  TIMESTAMPTZ  NOT NULL,
    end_time    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_region_id FOREIGN KEY (region_id) REFERENCES regions (id),
    CONSTRAINT chk_severity CHECK (severity IN ('yellow', 'orange', 'red')),
    CONSTRAINT chk_window CHECK (end_time > start_time)
);

-- Serves "region = X and active at time T" (the read-path query in slice 5).
CREATE INDEX idx_weather_warnings_region_active
    ON weather_warnings (region_id, start_time, end_time);
