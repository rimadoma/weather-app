# Weather — learning project

A backend-oriented learning project: fake real-time weather data flows from a
test generator through RabbitMQ into PostgreSQL, served by a read-only REST API
with a small Vue.js/Vuex frontend.

## Purpose and ground rules (important)

The developer's goal is to **learn jOOQ, Vue.js, and Vuex** and refresh Spring
Boot & PostgreSQL. Backend matters more than frontend. This shapes how AI must
collaborate here:

- Work iteratively, in minimal, human-digestible steps.
- Do NOT implement everything: provide boilerplate on request, review code and
  design, offer alternatives. The developer writes the instructive code.
- Generate only small pieces of code at a time, so they can be understood and
  questioned.
- Prefer clarifying questions over assumptions; stop when unclear.
- Exercise project — not production ready by design. Don't gold-plate.
- Always write in UK English

## Design docs = decision log

All design lives in `docs/` and is written as iterations, **newest first**:

- `docs/requirements.md` — requirements, ingest rules, API design, topology
- `docs/data-modelling.md` — DB schema and RabbitMQ message formats
- `docs/initial-context.md` — the collaboration brief

New design decisions go at the TOP of the relevant file under a new
`# Iteration N -- <summary>` header. Read the docs before proposing changes;
don't write new iterations until they have been discussed and agreed.

## Stack

Java 25, Spring Boot 4.x (Spring Framework 7), Maven 3.9.x, PostgreSQL via
jOOQ (ideally no raw SQL — accepted exceptions are documented in the docs),
RabbitMQ, Flyway. Frontend: Vue.js + Vuex.

## Planned module layout (iteration 6)

Maven multi-module: `weather-db` (Flyway migrations, jOOQ codegen, thin query
toolbox), `weather-materialiser` (RabbitMQ → DB ingest), `weather-api`
(read-only REST), `weather-generator` (DB seeder + fake message publisher).

Migrations run via Maven from weather-db, then jOOQ classes are regenerated.

## Dev environment

Near-term: docker-compose for PostgreSQL + RabbitMQ; apps run from the IDE.
Later milestone (not core design): deploy everything to Docker Desktop's
built-in Kubernetes with `skaffold dev`. Keep all config in environment
variables so that move stays cheap.
