package org.example.weather.materialiser.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

/**
 * A {@code weather_warning} message, per docs/data-modelling.md iteration 0.
 * Severity arrives capitalised (e.g. "Yellow") and is normalised to lower case
 * before insert; times are epoch seconds.
 */
@JsonRootName(value = "weather_warning")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherWarning(String region, String severity, String description, long startTime, long endTime) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public WeatherWarning(@JsonProperty("region") String region,
                          @JsonProperty("severity") String severity,
                          @JsonProperty("description") String description,
                          @JsonProperty("start_time") long startTime,
                          @JsonProperty("end_time") long endTime) {
        this.region = region;
        this.severity = severity;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
