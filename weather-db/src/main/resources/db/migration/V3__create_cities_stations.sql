CREATE TABLE cities (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    region_id BIGINT NOT NULL,
    lat DECIMAL(8, 6) NOT NULL,
    lng DECIMAL(9, 6) NOT NULL,
    CONSTRAINT fk_region_id FOREIGN KEY (region_id) REFERENCES regions(id),
    CONSTRAINT chk_lat CHECK (lat >= -90 AND lat <= 90),
    CONSTRAINT chk_lng CHECK (lng >= -180 AND lng <= 180)
);

CREATE TABLE stations (
    id   BIGSERIAL PRIMARY KEY,
    serial_no VARCHAR(50) NOT NULL UNIQUE,
    city_name VARCHAR(50) NULL,
    lat DECIMAL(8, 6) NULL,
    lng DECIMAL(9, 6) NULL,
    CONSTRAINT chk_lat CHECK (lat >= -90 AND lat <= 90),
    CONSTRAINT chk_lng CHECK (lng >= -180 AND lng <= 180),
    CONSTRAINT has_location CHECK (city_name IS NOT NULL OR (lat IS NOT NULL AND lng IS NOT NULL)),
    CONSTRAINT coord_pair CHECK ((lat IS NULL) = (lng IS NULL))
);

CREATE INDEX idx_stations_geo ON stations USING GIST (ll_to_earth(lat, lng));