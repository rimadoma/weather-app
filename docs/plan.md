# Implementation plan

Living document -- tick off steps as they land, amend freely. Unlike the design
docs, this file is edited in place (no iteration headers). Generated after requirements iteration 6.

The plan is organised as **vertical slices**: each feature is taken all the way
from generation through RabbitMQ, DB, and API to the frontend before the next
feature starts. Every slice ends with a test checkpoint -- unit tests for the
new logic plus a hands-on psql/curl session -- so the dev loop stays small.

## Phase 0 -- project skeleton & dev loop

- [x] Parent `pom.xml` + `weather-db` module skeleton
- [x] `docker compose up`; verify psql connection and RabbitMQ management UI
- [x] Flyway wired to run via Maven from weather-db; migration V1: extensions
      (cube, earthdistance)
- [x] Migration V2: `regions` (generated example)
- [x] Migration V3 (developer-written DDL practice): `cities` + `stations`
      incl. the location CHECK constraints and the GiST index
- [x] jOOQ codegen wired to run after migrations; inspect the generated classes
- [x] Test approach decided: plain unit tests (no DB) for pure logic -- XML
      parsing, unit normalisation, wind pairing, bucket maths; Testcontainers
      (singleton container, `@ServiceConnection`) for anything executing jOOQ
      against the schema. H2/mocked-SQL rejected: extensions, matviews, and
      enums need real Postgres. The compose DB stays an interactive
      playground, not a test target.
- [x] **Checkpoint:** migrate, regenerate, and query one table through jOOQ
      from a scratch test; poke the schema in psql -- Phase 0 complete

## Slice 1 -- current temperature, end to end

The thinnest possible vertical: temperature only, no wind, no warnings.

- [x] Migration: `scalar_measurements` + `station_cities` materialized view
- [x] `weather-generator` part 1: seed regions, cities, stations directly into
      the DB (bulk-insert jOOQ practice); refresh the matview once after seeding
- [x] `weather-generator` part 2: seed some recent temperature measurements
      directly into the DB, so the current-weather endpoint has data before
      any live messages have flowed
- [x] RabbitMQ topology: `stations` exchange, `stations.temperature` queue +
      binding declared via `definitions.json` (durable, so it exists before
      either app connects regardless of start order)
- [x] `weather-generator` part 3: publish fake `station_measurements` XML
      (temperature only for now) to RabbitMQ on a per-station schedule,
      continuing to simulate live data as we go (persistent messages,
      `delivery_mode: 2`)
- [x] `weather-materialiser`: consume, parse XML leniently, normalise °F to °C,
      skip unknown stations, insert
- [ ] `weather-api`: `GET /api/weather` with pagination -- temperature average
      over the past hour, null for cities without data (warnings key present
      but always empty for now)
- [ ] Frontend: minimal Vue + Vuex list page rendering the endpoint
- **Checkpoint:** unit tests for XML parsing, unit normalisation, and the skip
  rule; watch rows arrive in psql while the generator runs; curl the endpoint

## Slice 2 -- wind

- [ ] Migration: `wind_measurements`
- [ ] Generator: emit wind speed + direction entries (mixed m/s and mph)
- [ ] Materialiser: pairing rule -- match speed/direction by timestamp within
      one message, skip unpaired halves; normalise mph to m/s
- [ ] API: add wind speed (plain mean) and wind direction (vector/circular
      mean) to the list endpoint
- [ ] Frontend: show wind on the list page
- **Checkpoint:** unit tests for pairing and the circular mean (350° and 10°
  must average to 0°, not 180°); verify in psql that no NULL-direction rows exist

## Slice 3 -- station registration lifecycle

- [ ] Generator: publish `station_registration` messages for new stations
      (instead of only direct-seeding them)
- [ ] Materialiser: handle registration -- upsert station, refresh the matview
- [ ] Verify the skip-unknown-stations rule against a station that measures
      before registering
- **Checkpoint:** unit tests for registration parsing (city-only, coords-only,
  both); psql session on the matview: name match, 25 km geo match, overlap

## Slice 4 -- past week detail

- [x] Generator: seed ~a week of historic measurements directly into the DB
- [ ] API: `GET /api/weather/:id` -- fixed 6 h bucket grid (02/08/14/20 UTC),
      25 buckets, nulls for empty buckets, current partial bucket last
- [ ] Frontend: detail page with a simple line graph (temperature, wind speed,
      or both), not rendering past "now"
- **Checkpoint:** unit tests for bucket-boundary maths (overnight bucket
  crossing midnight, window start 6 days back); curl a city with sparse data
  and confirm nulls, not gaps

## Slice 5 -- weather warnings

- [ ] Migration: `weather_warnings`
- [ ] Generator: occasionally publish `weather_warning` messages (windows
      starting a day or two in the future)
- [ ] Materialiser: consume and insert
- [ ] API: attach active-at-query-time warnings per city in both endpoints
- [ ] Frontend: display warnings on both pages
- **Checkpoint:** unit tests for the active-window check (future warning is
  stored but not shown); insert a warning by hand in psql and see it appear

## Parked (later milestone, not core design)

- Deploy to Docker Desktop Kubernetes with `skaffold dev`
- `REFRESH ... CONCURRENTLY` upgrade for the matview
- Refactor RabbitMQ listeners and publishers into a shared lib module --
  the message schemas (`StationMeasurements`/`Measurement`) are duplicated
  between weather-generator and weather-materialiser for now and need to
  migrate into that shared module too, not just the listener/publisher code
