package org.example.weather.generator.schemas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

/**
 * A {@code weather_warning} message, per docs/data-modelling.md iteration 0.
 */
@JsonRootName(value = "weather_warning")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WeatherWarning {
    public final String region;
    public final String severity;
    public final String description;
    @JsonProperty("start_time")
    public final long startTime;
    @JsonProperty("end_time")
    public final long endTime;

    public WeatherWarning(String region, String severity, String description, long startTime, long endTime) {
        this.region = region;
        this.severity = severity;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
