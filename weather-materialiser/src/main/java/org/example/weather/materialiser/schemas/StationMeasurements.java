package org.example.weather.materialiser.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.ArrayList;
import java.util.List;

@JsonRootName(value = "station-measurements")
public class StationMeasurements {
    public final String serialNumber;

    @JacksonXmlElementWrapper(localName = "measurements")
    @JacksonXmlProperty(localName = "measurement")
    public final List<Measurement> measurements = new ArrayList<>();

    @JsonCreator
    public StationMeasurements(@JsonProperty("serialNumber") String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public record Measurement(String type, String value, String unit, long timestamp) {}
}
