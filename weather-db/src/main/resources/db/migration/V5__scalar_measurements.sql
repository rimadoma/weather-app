CREATE TABLE scalar_measurements
(
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(50)   NOT NULL,
    reading     DECIMAL(5, 1) NOT NULL,
    station_id  BIGINT        NOT NULL,
    measured_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_station_id FOREIGN KEY (station_id) REFERENCES stations (id),
    CONSTRAINT chk_type CHECK (type IN ('temperature'))
);

CREATE INDEX idx_scalar_measurements_station_id ON scalar_measurements (station_id);
CREATE INDEX idx_scalar_measurements_measured_at ON scalar_measurements (measured_at);