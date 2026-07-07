CREATE
MATERIALIZED VIEW station_cities AS
-- Rule 1: station declares its city by name.
WITH name_matches AS (
    SELECT cities.id as city_id, stations.id as station_id
    FROM cities
             INNER JOIN stations ON cities.name = stations.city_name
),
-- Rule 2: station is within 25km of a city's coordinates.
-- First, pre-filter candidates with a bounding box to make use of the GiST index
-- and then check that actual circular distance is < 25 km.
     geo_matches AS (
         SELECT cities.id as city_id, stations.id as station_id
         FROM cities
                  INNER JOIN stations
                             ON earth_box(ll_to_earth(cities.lat, cities.lng), 25000) @>
                                ll_to_earth(stations.lat, stations.lng)
                                 AND earth_distance(
                                             ll_to_earth(cities.lat, cities.lng),
                                             ll_to_earth(stations.lat, stations.lng)
                                     ) < 25000
     )
SELECT *
FROM name_matches
UNION
SELECT *
FROM geo_matches
WITH NO DATA;

CREATE UNIQUE INDEX idx_city_station ON station_cities(city_id, station_id);

