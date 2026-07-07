# Iteration 5 -- V3 constraint hardening (decided during implementation)
Small constraint decisions made while writing migration V3, backported here
rather than designed up front:
- `cities.name` gains UNIQUE -- the station_cities matview matches stations to
  cities by name; duplicate city names would make those matches murky.
- `cities` and `stations` gain range CHECKs: -90 <= lat <= 90,
  -180 <= lng <= 180. The DECIMAL(8,6)/(9,6) types alone happily store
  impossible coordinates like lat 99.999999.

# Iteration 4 -- region names are unique
Region names are (fictional) UK counties. `weather_warning` messages carry only
a region name, so warning ingest resolves regions by name -- duplicates would
make that lookup ambiguous.

### regions (revised)
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL, UNIQUE

# Iteration 3 -- station registration; station_cities materialized view
Changes from iteration 2: stations are keyed by the serial number from their registration message (name dropped -- the registration message carries none); location can be city name, coordinates, or both; city matching is precomputed into a materialized view refreshed at registration time.

### stations (revised)
* id, BIGSERIAL, PRIMARY KEY
* serial_no, VARCHAR(50), NOT NULL, UNIQUE -- from the registration message
* city_name, VARCHAR(50), NULL
* lat, DECIMAL(8, 6), NULL
* lng, DECIMAL(9, 6), NULL

CHECK: city_name IS NOT NULL OR (lat IS NOT NULL AND lng IS NOT NULL) -- must have at least one kind of location
CHECK: (lat IS NULL) = (lng IS NULL) -- coordinates come as a pair or not at all

Indices: GiST on ll_to_earth(lat, lng) -- serves the view refresh, not the read path

### station_cities (MATERIALIZED VIEW)
* city_id, BIGINT
* station_id, BIGINT

Definition: a station belongs to a city if its city_name matches the city's name, OR its coordinates are within 25 km of the city's (earth_distance). A station can match several cities; overlap is intended.

Unique index on (city_id, station_id) -- city-first to serve city-centric queries, and it satisfies the prerequisite for REFRESH ... CONCURRENTLY should we ever need it.

Refresh: plain REFRESH MATERIALIZED VIEW at station registration time.

# Iteration 2 -- measurements split into scalar + wind tables
Changes from iteration 1: the single measurements table is replaced by two tables along the "table per shape" principle. Scalars keep a type discriminator for future scalar types (humidity, pressure, ...); wind is a natural (speed, direction) tuple. Only the replaced table is shown -- everything else is unchanged from iteration 1.

### scalar_measurements
* id, BIGSERIAL, PRIMARY KEY
* type, ENUM('temperature'), NOT NULL -- future scalar types extend this enum
* reading, DECIMAL(5, 1), NOT NULL -- always normalised before insert (°C for temperature)
* station_id, BIGINT, NOT NULL, FOREIGN KEY(stations.id)
* measured_at, TIMESTAMPTZ, NOT NULL -- time of measurement, set by source, no default

Indices: station_id, measured_at

### wind_measurements
* id, BIGSERIAL, PRIMARY KEY
* station_id, BIGINT, NOT NULL, FOREIGN KEY(stations.id)
* speed, DECIMAL(5, 1), NOT NULL -- m/s, normalised before insert
* direction, SMALLINT, NOT NULL, CHECK (0 <= direction AND direction < 360) -- degrees, meteorological convention (blowing from, 0 = north)
* measured_at, TIMESTAMPTZ, NOT NULL -- time of measurement, set by source, no default

Indices: station_id, measured_at

Aggregation note: mean direction for the "current weather" view is a vector (circular) mean, not an arithmetic mean.

# Iteration 1 -- revised after review
Changes from iteration 0: FK columns are BIGINT (BIGSERIAL is only for the auto-incrementing PK itself); singular/plural naming unified to plural; JSONB location dropped in favour of typed nullable columns; POINT dropped in favour of lat/lng DECIMAL columns (plays better with both jOOQ and ll_to_earth); timestamps have no defaults and are NOT NULL; warnings gained a description; extra indices for the known query shapes.

Requires extensions: cube, earthdistance.

## Data model / schemas

### regions
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL

### cities
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL
* region_id, BIGINT, NOT NULL, FOREIGN KEY(regions.id)
* lat, DECIMAL(8, 6), NOT NULL
* lng, DECIMAL(9, 6), NOT NULL

### stations
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL
* city_name, VARCHAR(50), NULL
* lat, DECIMAL(8, 6), NULL
* lng, DECIMAL(9, 6), NULL

A station has either a city_name or coordinates (JSONB "future formats" idea parked for now).

Indices: GiST on ll_to_earth(lat, lng) -- serves the "within 25 km" query.

### measurements
* id, BIGSERIAL, PRIMARY KEY
* type, ENUM('temperature', 'windspeed'), NOT NULL
* reading, DECIMAL(5, 1), NOT NULL -- always °C or m/s, normalised before insert
* station_id, BIGINT, NOT NULL, FOREIGN KEY(stations.id)
* measured_at, TIMESTAMPTZ, NOT NULL -- time of measurement, set by source, no default

Indices: station_id, measured_at

### weather_warnings
* id, BIGSERIAL, PRIMARY KEY
* region_id, BIGINT, NOT NULL, FOREIGN KEY(regions.id)
* description, VARCHAR(100), NOT NULL -- e.g. flooding, forest fire risk, strong winds
* severity, ENUM('yellow', 'orange', 'red'), NOT NULL
* start_time, TIMESTAMPTZ, NOT NULL
* end_time, TIMESTAMPTZ, NOT NULL

Indices: (region_id, start_time, end_time) -- serves "region = X and active now"

# Iteration 0 -- initial developer modelling
## Messages
Serialised XML -- common IoT format
No hard schemas, pretend we're prepared for unknown future formats. Just try to find the fields we need
### station_registration
Can have city, coordinates, or both
```
<?xml version="1.0" encoding="UTF-8"?>
<station>
    <serialNumber>12312ABF323</serialNumber> 
    <location>
        <coordinates>
            <latitude>54.123456</latitude>
            <longitude>1.000000</longitude>
        </coordinates>
        <city>Berryham-upon-Wicket</city>
    </location>
</station>
```

### station_measurements
Can carry any mix of wind & temp measurements from the past hour. Units are either °C & m/s or °F and mph.
```
<?xml version="1.0" encoding="UTF-8"?>
<station_measurements>
    <serialNumber>12312ABF323</serialNumber> 
    <measurements>
        <measurement>
            <type>temperature</type>
            <value>+23.4</value>
            <unit>°C</unit>
            <timestamp>1710000000</timestamp>
        </measurement>
        <measurement>
            <type>wind_speed</type>
            <value>1.4</value>
            <unit>m/s</unit>
            <timestamp>1710000000</timestamp>
        </measurement>
        <measurement>
            <type>wind_direction</type>
            <value>40</value>
            <unit>degree</unit>
            <timestamp>1710000000</timestamp>
        </measurement>
    </measurements>
</station_measurements>
```

### weather_warning
```
<?xml version="1.0" encoding="UTF-8"?>
<weather_warning>
    <region>Northumberland</region> 
    <severity>Yellow</severity>
    <description>High winds</description>
    <start_time>1710000000</start_time>
    <end_time>1710100000</end_time>
</weather_warning>
```

## DB tables
### cities
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL
* region_id, BIGSERIAL, NOT_NULL, FOREIGN KEY(region.id)
* coordinates, POINT, DEFAULT (0, 0)

### station
* id, BIGSERIAL, PRIMARY KEY
* serialNo, VARCHAR(50), NOT NULL, UNIQUE
* location JSONB (can be {lat: 53.34, lng: 0.45}, {city: "Berrywick-upon-Scone"})

### measurements
* id, BIGSERIAL, PRIMARY KEY
* type, ENUM('temperature', 'windspeed')
* reading, DECIMAL(5, 1)
* station_id, BIGSERIAL, NOT_NULL, FOREIGN KEY(station.id)
* timestamp TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

Indices: station_id, timestamp

### weather_warnings
* id, BIGSERIAL, PRIMARY KEY
* region_id, BIGSERIAL, NOT_NULL, FOREIGN KEY(region.id)
* severity, ENUM('yellow', 'orange', 'red')
* start_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
* end_time TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP

Indices: region_id

### regions
* id, BIGSERIAL, PRIMARY KEY
* name, VARCHAR(50), NOT NULL