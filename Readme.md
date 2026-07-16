A web app that shows basic weather data for cities. Can view temperature and wind now or over the past week. Also displays active weather warnings. A learning project for brushing up on Java, Spring Boot, jOOQ, PostgreSQL and Vue.js. 
Includes a test data generator that can be used to seed historical data and to produce messages over RabbitMQ for "real time" measurements. I tried to work with Claude Code in a way where both the workflow and human-AI interaction would be as visible as possible. Project started from iteration 0 where I provided the initial requirements and context (see /docs).

# How to read this repo
The most instructive part of this project is not the code but the decision log in `/docs`. `requirements.md` and `data-modelling.md` are written as iterations, **newest first**: start at iteration 0 at the bottom and read upward to watch the design evolve. Nothing has been tidied up after the fact -- superseded decisions and outright reversals are left standing with their reasons stated (e.g. requirements iteration 15 undoing iteration 10, or Vuex being dropped in iteration 13). If you are early in your career: this back-and-forth is what software architecture actually looks like. There is no perfect design, just reasonable assumptions, missteps, and revisions. `plan.md` shows the order everything was built in (vertical slices, each feature end-to-end), and `initial-context.md` is the brief that set the human--AI collaboration rules.

# Architecture
A Maven multi-module backend plus a Vue frontend. Data flows one way: the
generator publishes fake measurements to RabbitMQ, the materialiser writes them
into PostgreSQL, and the API serves them to the frontend.

- **weather-db** -- Flyway migrations and jOOQ code generation for the schema, plus a thin shared query toolbox. The other modules build on its generated classes.
- **weather-generator** -- seeds regions, cities and stations (and historic data) directly into the DB, then publishes fake "real-time" measurement messages to RabbitMQ.
- **weather-materialiser** -- consumes those messages, parses and normalises them (units, wind pairing, skip rules), and writes them to PostgreSQL.
- **weather-api** -- read-only REST API over the DB, written in jOOQ; its contract is the hand-authored OpenAPI spec that both sides generate from.
- **weather-frontend** -- Vue.js (Vite) single-page app that consumes the REST API. Two pages: the city list and a per-city detail view.
- **PostgreSQL** -- normalised store for regions, cities, stations, measurements and weather warnings.
- **RabbitMQ** -- message broker carrying station registrations, station measurements and regional weather warnings from the generator to the materialiser. Topology in 'infra/'.

# How to dev
1. Start infra (PostgreSQL & RabbitMQ) (`docker compose up`)
2. Run migrations `mvn -pl weather-db flyway:migrate` (one time prerequisite)
3. Build modules `mvn clean install` (jOOQ code generation needs steps #1 and #2)
4. Run weather-generator if you need to seed the DB and publish test messages
5. Run the materialiser if you want test messages to be written to the DB
6. Run the API
7. Install frontend deps `npm install` (from `weather-frontend/`)
8. Generate the frontend API types `npm run gen:api` (rerun whenever `weather-api/src/main/resources/openapi/weather-api.yaml` changes)
9. Start the frontend with `npm run dev`
