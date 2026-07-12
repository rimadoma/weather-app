CREATE TABLE wind_measurements
(
    id          BIGSERIAL PRIMARY KEY,
    station_id  BIGINT        NOT NULL,
    speed       DECIMAL(5, 1) NOT NULL,
    direction   SMALLINT      NOT NULL,
    measured_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_station_id FOREIGN KEY (station_id) REFERENCES stations (id),
    CONSTRAINT chk_speed CHECK (speed >= 0),
    CONSTRAINT chk_direction CHECK (0 <= direction AND direction < 360)
);

CREATE INDEX idx_wind_measurements_station_id ON wind_measurements (station_id);
CREATE INDEX idx_wind_measurements_measured_at ON wind_measurements (measured_at);
