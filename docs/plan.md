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
- [x] ~~`weather-api`: `GET /api/cities` first -- paginated city catalog (id,
      name), 25/page, alphabetical, `totalCities` via `COUNT(*) OVER()`~~
      (later removed in requirements iteration 15: the frontend only needed
      `totalCities`, so it moved back onto `/api/weather` and this endpoint
      was dropped)
- [x] `weather-api`: `GET /api/weather` -- paginated (25/page, alphabetical,
      no id list passed in), temperature average over the past hour, null for
      cities without data (warnings key present but always empty for now),
      `totalCities` via `COUNT(*) OVER()` (fall back to a plain count on an
      out-of-range page)
- [x] Frontend: Vite + Vue Router skeleton; minimal list page (landing)
      rendering current weather from `/api/weather`, with query-param
      pagination (`?page=N`, active page highlighted)
- **Checkpoint:** unit tests for XML parsing, unit normalisation, and the skip
  rule; watch rows arrive in psql while the generator runs; curl the endpoint

## Slice 2 -- wind

- [x] Migration: `wind_measurements`
- [x] Generator: seed & emit wind speed + direction entries (mixed m/s and mph)
- [x] Materialiser: pairing rule -- match speed/direction by timestamp within
      one message, skip unpaired halves; normalise mph to m/s
- [x] API: add wind speed (plain mean) and wind direction (speed-weighted
      vector mean -- requirements iteration 18) to the list endpoint
- [x] Frontend: show wind on the list page
- **Checkpoint:** unit tests for pairing and the speed-weighted vector
  direction mean (a stronger wind veers the mean toward it; 350° and 10°
  resolve near north, never the 180° a naive arithmetic mean gives --
  requirements iteration 18); verify in psql that no NULL-direction rows exist

## Slice 3 -- station registration lifecycle

- [x] Generator: publish `station_registration` messages for new stations
      (instead of only direct-seeding them)
- [x] Materialiser: handle registration -- upsert station, refresh the matview
- [x] Verify the skip-unknown-stations rule against a station that measures
      before registering
- **Checkpoint:** unit tests for registration parsing (city-only, coords-only,
  both); psql session on the matview: name match, 25 km geo match, overlap

## Slice 4 -- past week detail

- [x] Generator: seed ~a week of historic measurements directly into the DB
- [x] API: `GET /api/weather/:id` -- fixed 6 h bucket grid (02/08/14/20 UTC),
      25 buckets, nulls for empty buckets, current partial bucket last
- [x] Frontend: detail page with a simple line graph (temperature, wind speed,
      or both), not rendering past "now"
- **Checkpoint:** unit tests for bucket-boundary maths (overnight bucket
  crossing midnight, window start 6 days back); curl a city with sparse data
  and confirm nulls, not gaps

## Slice 5 -- weather warnings

- [x] Migration: `weather_warnings`
- [x] Generator: seed some warnings active now (~1/4 of regions, 25% chance of
      more than one each). Publish `weather_warning` messages on a rare schedule
      via the `metoffice` exchange, each starting now and active for 24--72 h
- [x] Materialiser: consume and insert (lenient parse, normalise severity,
      validate the window in code so the chk_window CHECK never throws, skip
      unknown regions)
- [x] API: attach active-at-query-time warnings per city in both endpoints
      (list + detail; active window is start <= now < end, end exclusive)
- [x] Frontend: display warnings on both pages (list: severity-coloured
      warning-sign icons, one per type, + hedgehog-uprising easter egg;
      detail: one per-severity box above the chart listing its descriptions)
- **Checkpoint:** unit tests for the active-window check (future warning is
  stored but not shown); insert a warning by hand in psql and see it appear

## Future work?

- Frontend: a reusable error-blurb component for failed fetches. The list
  (and detail) views currently `await` the API with no try/catch, so a 500
  -- which the read side deliberately lets bubble (the error-handling block
  above requirements iteration 11) -- or a network blip leaves the view
  stale and throws an unhandled rejection. Wrap the fetches and render a
  friendly "couldn't load weather" message via one shared component on both
  pages.
- CI: a single GitHub Actions workflow running `mvn verify` (Testcontainers
  already covers the DB-touching tests, so the workflow needs no service
  containers of its own) plus the frontend's `vue-tsc` type-check. Everything
  currently runs only from the IDE/local Maven; one pipeline would catch a
  broken build or a spec/type drift on push.
- Deploy to Docker Desktop Kubernetes with `skaffold dev`
- `REFRESH ... CONCURRENTLY` upgrade for the matview
- Common backend lib for DB / RabbitMQ / Schemas etc
