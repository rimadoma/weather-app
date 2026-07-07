A web app that shows basic weather data for cities. Can view temperature and wind now or over the past week. Also displays active weather warnings. A learning project for brushing up on Java, Spring Boot, jOOQ, PostGreSQL and Vue.js. 
Includes a test data generator that can be used to seed historical data and to produce messages over RabbitMQ for "real time" measurements. I tried to work with Claude Code in a way where both the workflow and human-AI interaction would be as visible as possible. Project started from iteration 0 where I provided the initial requirements and context (see /docs).

# How to dev
1. Start PostGreSql (`docker compose up`)
2. Run migrations `mvn -pl weather-db flyway:migrate` (one time prerequisite)
3. `mvn clean install` (jOOQ code generation needs steps #1 and #3)
