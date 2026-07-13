package org.example.weather.generator.schemas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRootName;

@JsonRootName(value = "station")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StationRegistration {
    public final String serialNumber;
    public final Location location;

    public StationRegistration(String serialNumber, Location location) {
        this.serialNumber = serialNumber;
        this.location = location;
    }

    public record Location(Coordinates coordinates, String city) {}

    public record Coordinates(String latitude, String longitude) {}
}
