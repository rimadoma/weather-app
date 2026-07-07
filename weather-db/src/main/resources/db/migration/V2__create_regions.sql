-- Schema per docs/data-modelling.md, iteration 4.
-- Names are (fictional) UK counties; weather_warning messages reference
-- regions by name only, so the name must identify one region.
CREATE TABLE regions (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);
