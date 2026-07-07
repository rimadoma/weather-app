-- cube + earthdistance power the 25 km station-to-city matching.
-- Their use is quarantined to the station_cities materialized view
-- definition (docs/requirements.md, iteration 3).
CREATE EXTENSION IF NOT EXISTS cube;
CREATE EXTENSION IF NOT EXISTS earthdistance;
