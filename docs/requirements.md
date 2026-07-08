# Iteration 8 -- RabbitMQ definitions.json must be self-contained
Refines iteration 7's `definitions.json` decision, based on a gotcha hit in
a prior project:
- The definition is self-contained: vhost `/`, the `weather`/`weather` user,
  and its permissions are declared inside `definitions.json` itself, not
  left to `RABBITMQ_DEFAULT_USER`/`PASS` in compose.yaml. Two competing
  bootstrap paths (env-var default-user creation and definitions import) can
  silently conflict -- confirmed by RabbitMQ's own boot log, which detects a
  definitions file and logs "Will not seed default virtual host and user:
  have definitions to load...", skipping the env-var path entirely.
- Decided to use 'direct' exchanges instead of 'topics'. Since each exchange is a domain like 
  "weather station measurement data", and route specific kind of data, can't
  really imagine a scenario where we'd route to 'station.*'

# Iteration 7 -- RabbitMQ topology
- Topology (exchanges, queues, bindings) is declared via RabbitMQ's
  `definitions.json`, loaded at broker boot -- not auto-declared by either
  app on connect. This decouples topology existence from app startup order:
  the generator is meant to launch before the materialiser, so the queue
  must already exist and be bound by the time the first message is
  published, or a topic/direct exchange just silently drops it. Default
  vhost `/` and `weather`/`weather` credentials stay as already set via
  compose.yaml env vars; `definitions.json` only adds exchanges/queues/bindings.
- One exchange per message domain, not per unit-of-measurement. `stations`
  covers everything a station itself tells us: starts with routing key
  `stations.temperature` -> `stations.temperature.queue` (Slice 1), expanding
  to `stations.wind` (Slice 2) and `stations.registration` (Slice 3).
  `weather_warning` messages are region-scoped, not station-scoped, so they
  get their own exchange (name TBD) when Slice 5 lands rather than being
  folded into `stations`.
- Queues are `durable: true` and messages are published with
  `delivery_mode: 2` (persistent) -- correctness practice for surviving a
  materialiser restart while the broker keeps running. No RabbitMQ PVC in
  this project, so none of this survives a full broker container restart --
  consistent with the original brief's "doesn't have to ... survive app
  crashing."

# Iteration 6 -- application topology and module layout
Maven multi-module project:
- **weather-db** -- Flyway migrations, jOOQ codegen, and a thin query toolbox. The toolbox exposes helpers only for what is genuinely PostgreSQL-gnarly or shared (earth_distance raw-SQL template, vector-mean expression, 6h-bucket floor expression, matview refresh). Apps write their own jOOQ queries against the generated classes -- deliberately NOT a full DAO facade: jOOQ's typed DSL is already the abstraction, and the instructive queries should live in the apps where they belong.
- **weather-materialiser** -- RabbitMQ listener: registration, measurements, warnings; normalisation; wind pairing; skip rules; matview refresh on registration.
- **weather-api** -- read-only REST API (the two endpoints of iteration 5).
- **weather-generator** -- seeds regions/cities/stations and historic measurements directly into the DB, then publishes fake real-time messages to RabbitMQ.

Decisions:
- API and materialiser are separate apps. Ceremony accepted deliberately as real-world rehearsal: ingest is write-heavy and bursty, reads are light, so materialisers would scale independently of the API; the queue buffers during materialiser downtime.
- Seeding is done via direct DB writes, not a message flood. Cities/regions have no message type, so direct seeding is unavoidable anyway; bulk inserts are faster and give a second flavour of jOOQ practice. Caveat noted: this makes generator and materialiser both writers to the schema -- a mild single-writer violation that real projects also accept for seed/test tooling.
- Migrations are run via Maven from weather-db (migrate, then regenerate jOOQ classes), not at app startup. No app-start-order coupling.

# Iteration 5 -- read-side API design
## Aggregation rules
- Current weather (list endpoint): aggregated over [query_time - 1h, query_time]. Temperature and wind speed are plain averages; wind direction is a vector (circular) mean.
- Past week (detail endpoint): fixed 6-hour bucket grid with boundaries at 02:00, 08:00, 14:00, 20:00 UTC; the overnight bucket [20:00, 02:00) crosses midnight. Window: window_start = start of the current bucket - 6 days, window = [window_start, now]. That is 24 complete buckets plus the current partial one, touching 7 calendar days including today.
- Whole-bucket aggregation, no clipping: the window edge is always a bucket boundary by construction. Future times simply have no measurements; the frontend knows not to render past "now".
- Missing data is null, never omitted: a city with no measurements in an interval still appears, with null readings. Same for individual empty buckets.
- Warnings: only warnings active at query time (start_time <= now <= end_time), in both endpoints, top-level per city -- no historical/per-bucket warnings. A warning object carries description, severity, start_time, end_time.

## Pagination
- Page-number style (?page=N), 25 cities per page, ordered alphabetically by city name. Cursor pagination's benefit (stability under insertion) is moot since adding cities is out of scope.
- Paginate first, aggregate after: select the page's 25 cities, then compute aggregates only for those.

## Response shapes
API conventions: JSON, camelCase, timestamps as ISO-8601 UTC strings (the epoch-seconds format stays in the ingest XML only).

GET /api/weather?page=1
```json
{
  "metadata": {
    "page": 1,
    "pageSize": 25,
    "totalCities": 100
  },
  "cities": [
    {
      "id": 42,
      "name": "Berryham-upon-Wicket",
      "temperature": 23.4,
      "windSpeed": 1.4,
      "windDirection": 40,
      "warnings": [
        {
          "description": "High winds",
          "severity": "yellow",
          "startTime": "2026-07-06T00:00:00Z",
          "endTime": "2026-07-09T00:00:00Z"
        }
      ]
    },
    {
      "id": 57,
      "name": "Sconeham-upon-Wicket",
      "temperature": null,
      "windSpeed": null,
      "windDirection": null,
      "warnings": []
    }
  ]
}
```

GET /api/weather/42 (queried 2026-07-07 ~08:50 UTC)
```json
{
  "id": 42,
  "name": "Berryham-upon-Wicket",
  "warnings": [],
  "buckets": [
    { "startTime": "2026-07-01T08:00:00Z", "temperature": 22.1, "windSpeed": 3.2, "windDirection": 187 },
    { "startTime": "2026-07-01T14:00:00Z", "temperature": null, "windSpeed": null, "windDirection": null },
    { "startTime": "2026-07-07T08:00:00Z", "temperature": 23.0, "windSpeed": 1.1, "windDirection": 42 }
  ]
}
```
(25 bucket entries in reality; abbreviated here. Last entry is the current, partial bucket.)

# Iteration 4 -- wind pairing rule at ingest
- Wind speed and direction arrive as separate measurement entries in the same station_measurements message. Ingest pairs them by timestamp within that message to form one wind_measurements row.
- If either half of a pair is missing for a given timestamp, both are skipped -- no NULL-direction rows, no cross-message buffering.
- Message structure is uniform: every measurement entry carries type, value, unit, timestamp.

# Iteration 3 -- station registration; precomputed city matching
- Stations have a lifecycle: a station must send a registration message (carrying its serial number and location) before its measurements are accepted. Measurements from unknown stations are skipped, not stored. New stations appear rarely (max ~5/day).
- Station-to-city matching (name match + 25 km geo-near) is precomputed into a materialized view instead of being evaluated per query. Reads become plain joins -- pure jOOQ, no earthdistance calls in the hot path; the extension usage is quarantined inside the view definition.
- The view is refreshed at station registration time (plain REFRESH; at this scale it's milliseconds). REFRESH ... CONCURRENTLY is the documented upgrade path if we pretend-scale later -- it requires a unique index on the view and cannot run inside a transaction.
- "Add a new city" (and any resulting rebuild) is out of scope.

# Iteration 2 -- wind gains direction; measurement storage split
- Wind measurements are now a tuple: speed (m/s) and direction (whole degrees, meteorological convention: the direction the wind blows *from*, 0 = north). Stations publish speed and direction together.
- The city-level "current weather" view includes mean wind direction. Direction must be averaged as a vector (circular) mean -- average the sines and cosines, atan2 back to degrees. A naive arithmetic mean of angles is wrong (350° and 10° would average to 180°).
- Measurement storage follows table-per-shape: one table for scalar measurements (currently just temperature), one for wind tuples. Freeform JSONB readings were considered and rejected: no shape enforcement, awkward arithmetic, poor indexing, and it forfeits jOOQ's typed columns. If a future type has a different shape (e.g. sunrise/sunset), it likely has different grain too and gets its own table.

# Iteration 1 -- decisions from initial review
- Stack confirmed: Java 25, Spring Boot 4.x (Spring Framework 7), Maven 3.9.x.
- The 25 km "geo near" match uses the PostgreSQL earthdistance + cube extensions. Accepted compromise: jOOQ has no native support for them, so those calls need plain-SQL templates despite the "ideally no raw SQL" goal. Chosen over a pure-jOOQ haversine because earthdistance can use a GiST index — we pretend the system must scale to a lot of cities.
- A station within 25 km of multiple cities counts towards all of them (overlap is allowed).
- Units: all readings are normalised to °C and m/s before persisting. No unit column in the DB; this is an implicit contract of the measurements table.
- Measurement timestamps are the time of measurement, set by the data source. No DB defaults -- a missing timestamp is a bug we want to surface, not paper over.
- Weather warnings are issued ahead of time, so their windows are typically in the future at insert. Applicability is checked at query time: start_time <= now <= end_time.
- "Current" weather for a city = average of its stations' readings over the past hour.

# Iteration 0 -- initial developer written reqs 
A backend oriented, learning project for weather data. Java 25, Spring Boot (Spring 4.x), and Maven (3.9.x) backend that listens for fake real time weather data from a messaging system and writes to PostGreSQL DB. PostGreSQL is used via jooq -- ideally no raw SQL. Very light and simple Vue.js / Vuex frontend that shows current weather data at the given city and past data over the last week with very simple line graph visualisation. Shows either wind speed (m/s), temperature (*C) or both. Also shows any applicable weather warnings such as flooding, forest fire risk, or strong winds. Backend apps have to ensure data is normalised to these units before saving to the DB.

Backend also provides a simple read only REST API for getting weather data. End points GET /api/weather (current weather data summary for all cities, paginated at 25 cities per query), GET /api/weather/:id for the past week data of the given city. Frontend only has two pages corresponding to these two views of the data.  

Another part of the project is a test data generator that can be used to seed the DB plus generate "real time" measurement data. That is, it pushes fake test data into RabbitMQ every 15 minutes for each measurement station. There are a hundred cities and each can have several associated stations. Each station publishes at its own schedule. Minimal scheduling inside the test generator. Doesn't have to be production ready or survive app crashing. Each station publishes temperature and wind speed measurements separately. Temps can be in *C of *F. Wind speed in m/s or mph. Weather warnings are issued seldom, but they have a duration of several days. They're issued a day or two ahead of time. We should only show them if they are applicable at the time of viewing. All locations are imaginary British sounding places like Sconeham-upon-Wicket.

Measurements belong to a station, and each station has a location either as city name or coordinates (lat lng of "centre" point). They also have to allow for unknown future formats. Any data within 25 km of a city is assumed to belong to it. Locations can overlap. Weather warnings are assigned per region. Thus, when we receive a backend query, we have to do a match on city name, "geo near" query, and check for any applicable regional weather warnings. If a city has multiple measurements, all readings are averaged. DB data is highly normalised.

Out-of-scope:
- Timezone conversions: all data is in UTC and displayed in UTC.
- International support: all data is assumed to be from (a fictional version of) the UK
- Separate time series oriented DB for measurement data 