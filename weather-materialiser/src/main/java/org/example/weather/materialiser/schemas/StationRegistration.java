package org.example.weather.materialiser.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

@JsonRootName(value = "station")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StationRegistration(String serialNumber, Location location) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public StationRegistration(@JsonProperty("serialNumber") String serialNumber,
                               @JsonProperty("location") Location location) {
        this.serialNumber = serialNumber;
        this.location = location;
    }

    public record Location(Coordinates coordinates, String city) {
    }

    public record Coordinates(String latitude, String longitude) {
    }
}
