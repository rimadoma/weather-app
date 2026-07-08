package org.example.weather.generator.publishers;

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

    public StationMeasurements(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public void addMeasurement(Measurement measurement) {
        measurements.add(measurement);
    }

    public record Measurement(String type, String value, String unit, long timestamp) {}
}
