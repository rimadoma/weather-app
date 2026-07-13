package org.example.weather.db.toolbox;

import org.jooq.Field;

import java.math.BigDecimal;

import static org.example.weather.db.generated.Tables.WIND_MEASUREMENTS;
import static org.jooq.impl.DSL.avg;
import static org.jooq.impl.DSL.cos;
import static org.jooq.impl.DSL.rad;
import static org.jooq.impl.DSL.sin;
import static org.jooq.impl.DSL.sum;

/**
 * Shared jOOQ aggregate expressions over wind_measurements, dropped into the
 * apps' own grouped queries (per-city on the list endpoint, per-bucket on the
 * detail endpoint). Direction is left as its two summed vector components
 * (requirements iteration 18): SQL only does the set-based summation, and
 * turning the components back into a bearing is the caller's business logic.
 */
public final class WindAggregates {

    private WindAggregates() {
    }

    /** Plain mean wind speed in m/s. */
    public static Field<BigDecimal> meanSpeed() {
        return avg(WIND_MEASUREMENTS.SPEED);
    }

    /** Cosine component of the speed-weighted direction vector: sum(speed * cos(direction)). */
    public static Field<BigDecimal> directionVectorX() {
        return sum(WIND_MEASUREMENTS.SPEED.times(cos(rad(WIND_MEASUREMENTS.DIRECTION))));
    }

    /** Sine component of the speed-weighted direction vector: sum(speed * sin(direction)). */
    public static Field<BigDecimal> directionVectorY() {
        return sum(WIND_MEASUREMENTS.SPEED.times(sin(rad(WIND_MEASUREMENTS.DIRECTION))));
    }
}
